package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.observe.ObservationHandler.Observation;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolProgress;
import io.agentweave.core.tool.ToolSpec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An MCP client {@link ToolConnector}, built on the official MCP Java SDK, over the stdio
 * transport (the server runs as a subprocess) or the Streamable HTTP transport. It negotiates
 * the protocol revision and exposes the server's tools through the protocol-neutral core API.
 *
 * <pre>{@code
 * try (ToolConnector mcp = McpClientConnector.stdio("npx", "-y", "@modelcontextprotocol/server-everything").build()) {
 *     mcp.start();
 *     ToolOutcome out = mcp.invoke(ToolInvocation.of("echo", Map.of("message", "hi")));
 * }
 * try (ToolConnector mcp = McpClientConnector.http("https://tools.example.com").build()) { ... }
 * }</pre>
 *
 * <p>Beyond tools it reads resources, renders prompts, requests completions and receives log,
 * progress and list-change notifications; each fails with {@code UNSUPPORTED} when the server
 * didn't declare the capability. Thread-safe once started. See {@link #compliance()} for
 * exactly what is implemented.
 */
public final class McpClientConnector implements ToolConnector {

    private final ClientTransport transport;
    private final String target;
    private final Duration requestTimeout;
    private final String clientName;
    private final String clientVersion;
    private final CredentialProvider credentials;
    private final ObservationHandler observations;
    private final ComplianceReport baseReport;

    private volatile McpSyncClient client;
    private volatile ComplianceReport report;
    private volatile McpSchema.ServerCapabilities capabilities;
    private final ClientListeners listeners = new ClientListeners();

    private McpClientConnector(ClientTransport transport, Settings settings) {
        this.transport = transport;
        this.target = transport.observationTarget();
        this.requestTimeout = settings.requestTimeout;
        this.clientName = settings.clientName;
        this.clientVersion = settings.clientVersion;
        this.credentials = settings.credentials;
        this.observations = settings.observations;
        Properties sdk = sdkProperties();
        this.baseReport = new ComplianceReport(ProtocolId.MCP, sdk.getProperty("mcp.sdk.name"),
                sdk.getProperty("mcp.sdk.version"), transport.protocolVersions(), null,
                McpFeatures.clientSupported(transport.featureId(), transport.authorizes()),
                McpFeatures.clientUnsupported(transport.featureId(), transport.authorizes()));
        this.report = baseReport;
    }

    /** Starts building a connector that launches {@code command} as an stdio MCP server. */
    public static Builder stdio(String command, String... args) {
        return new Builder(command, List.of(args));
    }

    /**
     * Starts building a connector for a Streamable HTTP MCP server.
     *
     * @param baseUrl the server's base URL, for example {@code https://tools.example.com}; the
     *                endpoint path (default {@code /mcp}) is resolved against it
     */
    public static HttpBuilder http(String baseUrl) {
        return new HttpBuilder(baseUrl);
    }

    @Override
    public ProtocolId protocol() {
        return ProtocolId.MCP;
    }

    @Override
    public synchronized void start() {
        if (client != null) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.MCP, "connector already started");
        }
        McpClientTransport sdkTransport = listeners.tap(transport.open(
                credentials.credentialsFor(ProtocolId.MCP, transport.credentialTarget()), requestTimeout));
        McpClient.SyncSpec spec = McpClient.sync(sdkTransport)
                .requestTimeout(requestTimeout)
                .initializationTimeout(requestTimeout)
                .clientInfo(McpSchema.Implementation.builder(clientName, clientVersion).build());
        listeners.install(spec);
        McpSyncClient sync = spec.build();
        try {
            McpSchema.InitializeResult init = observe("initialize", target, sync::initialize);
            capabilities = init.capabilities() != null ? init.capabilities()
                    : McpSchema.ServerCapabilities.builder().build();
            report = baseReport.withNegotiatedRevision(init.protocolVersion());
            client = sync;
        } catch (RuntimeException e) {
            sync.close();
            throw e;
        }
    }

    @Override
    public ComplianceReport compliance() {
        return report;
    }

    @Override
    public List<ToolSpec> listTools() {
        McpSyncClient c = require(McpFeatures.TOOLS_LIST, "tools", caps -> caps.tools() != null);
        return observe("tools/list", "", c::listTools).tools().stream().map(McpContent::toToolSpec).toList();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, null, ToolProgress.NOOP);
    }

    /**
     * Calls a tool, forwarding the server's {@code notifications/progress} for this call to
     * {@code progress}. A {@code progressToken} is sent only when {@code progress} isn't
     * {@link ToolProgress#NOOP}. The caller is not sent: MCP has no field for it.
     */
    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
        Objects.requireNonNull(invocation, "invocation");
        McpSyncClient c = require(McpFeatures.TOOLS_CALL, "tools", caps -> caps.tools() != null);
        McpSchema.CallToolRequest.Builder request = McpSchema.CallToolRequest.builder(invocation.toolName())
                .arguments(invocation.arguments());
        String token = progress == null || progress == ToolProgress.NOOP ? null : UUID.randomUUID().toString();
        if (token != null) {
            request.progressToken(token);
            listeners.track(token, progress);
        }
        McpSchema.CallToolResult result;
        try {
            result = observe("tools/call", invocation.toolName(), () -> c.callTool(request.build()));
        } finally {
            if (token != null) {
                listeners.untrack(token);
            }
        }
        List<Content> content = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content part : result.content()) {
                content.addAll(McpContent.toNeutral(part));
            }
        }
        return new ToolOutcome(content, Boolean.TRUE.equals(result.isError()), result.structuredContent());
    }

    // Resources, prompts, completion and logging (see SPEC.md); each requires the server's capability.

    /** Every resource the server lists ({@code resources/list}, all pages). */
    public List<McpResourceInfo> listResources() {
        McpSyncClient c = require(McpFeatures.RESOURCES, "resources", caps -> caps.resources() != null);
        return observe("resources/list", "", c::listResources).resources().stream().map(ClientListeners::toInfo).toList();
    }

    /** Every resource template the server lists ({@code resources/templates/list}, all pages). */
    public List<McpTemplateInfo> listResourceTemplates() {
        McpSyncClient c = require(McpFeatures.RESOURCES, "resources", caps -> caps.resources() != null);
        return observe("resources/templates/list", "", c::listResourceTemplates).resourceTemplates().stream()
                .map(ClientListeners::toInfo).toList();
    }

    /** Reads a resource; an unknown URI fails with {@code INVALID_ARGUMENTS} ({@code -32002}). */
    public List<McpResourceContents> readResource(String uri) {
        Objects.requireNonNull(uri, "uri");
        McpSyncClient c = require(McpFeatures.RESOURCES, "resources", caps -> caps.resources() != null);
        return observe("resources/read", uri, () -> c.readResource(McpSchema.ReadResourceRequest.builder(uri).build()))
                .contents().stream().map(McpContent::toNeutral).toList();
    }

    /**
     * Subscribes to a resource. On each {@code notifications/resources/updated} for it, the
     * resource is re-read and {@code onUpdate} receives its contents. One listener per URI.
     */
    public void subscribe(String uri, Consumer<List<McpResourceContents>> onUpdate) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(onUpdate, "onUpdate");
        McpSyncClient c = require(McpFeatures.RESOURCES_SUBSCRIBE, "resources.subscribe",
                caps -> caps.resources() != null && Boolean.TRUE.equals(caps.resources().subscribe()));
        listeners.subscribe(uri, onUpdate);
        try {
            observe("resources/subscribe", uri, () -> {
                c.subscribeResource(McpSchema.SubscribeRequest.builder(uri).build());
                return null;
            });
        } catch (RuntimeException e) {
            listeners.unsubscribe(uri);
            throw e;
        }
    }

    public void unsubscribe(String uri) {
        Objects.requireNonNull(uri, "uri");
        McpSyncClient c = require(McpFeatures.RESOURCES_SUBSCRIBE, "resources.subscribe",
                caps -> caps.resources() != null && Boolean.TRUE.equals(caps.resources().subscribe()));
        listeners.unsubscribe(uri);
        observe("resources/unsubscribe", uri, () -> {
            c.unsubscribeResource(McpSchema.UnsubscribeRequest.builder(uri).build());
            return null;
        });
    }

    /** Every prompt the server lists ({@code prompts/list}, all pages). */
    public List<McpPromptInfo> listPrompts() {
        McpSyncClient c = require(McpFeatures.PROMPTS, "prompts", caps -> caps.prompts() != null);
        return observe("prompts/list", "", c::listPrompts).prompts().stream().map(ClientListeners::toInfo).toList();
    }

    /** Renders a prompt; an unknown prompt or a missing argument fails with {@code INVALID_ARGUMENTS}. */
    public McpPromptResult getPrompt(String name, Map<String, String> arguments) {
        Objects.requireNonNull(name, "name");
        McpSyncClient c = require(McpFeatures.PROMPTS, "prompts", caps -> caps.prompts() != null);
        Map<String, Object> args = arguments == null ? Map.of() : Map.copyOf(arguments);
        McpSchema.GetPromptResult result = observe("prompts/get", name,
                () -> c.getPrompt(McpSchema.GetPromptRequest.builder(name).arguments(args).build()));
        return new McpPromptResult(result.description(), result.messages() == null ? List.of()
                : result.messages().stream().map(McpContent::toNeutral).toList());
    }

    /** Suggests values for an argument of {@code prompt}, given what was typed and the other arguments. */
    public McpCompletion completePromptArgument(String prompt, String argument, String value,
                                                Map<String, String> context) {
        return complete(new McpSchema.PromptReference(prompt), argument, value, context);
    }

    /** Suggests values for a variable of a resource template, given what was typed and the other variables. */
    public McpCompletion completeTemplateVariable(String uriTemplate, String variable, String value,
                                                  Map<String, String> context) {
        return complete(new McpSchema.ResourceReference(uriTemplate), variable, value, context);
    }

    private McpCompletion complete(McpSchema.CompleteReference ref, String argument, String value,
                                   Map<String, String> context) {
        McpSyncClient c = require(McpFeatures.COMPLETION, "completions", caps -> caps.completions() != null);
        McpSchema.CompleteRequest request = new McpSchema.CompleteRequest(ref,
                new McpSchema.CompleteRequest.CompleteArgument(argument, value == null ? "" : value), null,
                context == null || context.isEmpty() ? null : new McpSchema.CompleteRequest.CompleteContext(context));
        McpSchema.CompleteResult.CompleteCompletion result = observe("completion/complete", McpContent.refName(ref),
                () -> c.completeCompletion(request)).completion();
        return new McpCompletion(result.values(), result.total(), Boolean.TRUE.equals(result.hasMore()));
    }

    /** Asks the server to send log messages at {@code level} and above ({@code logging/setLevel}). */
    public void setLogLevel(ToolProgress.Level level) {
        Objects.requireNonNull(level, "level");
        McpSyncClient c = require(McpFeatures.LOGGING, "logging", caps -> caps.logging() != null);
        observe("logging/setLevel", level.name(), () -> {
            c.setLoggingLevel(McpSchema.LoggingLevel.valueOf(level.name()));
            return null;
        });
    }

    /**
     * Receives every log message the server sends, in arrival order on the transport's thread,
     * so keep it quick. Register before or after {@link #start()}.
     */
    public void onLog(Consumer<McpLogMessage> listener) {
        listeners.onLog(Objects.requireNonNull(listener, "listener"));
    }

    /** Receives the server's tool list each time it announces a change. */
    public void onToolsChanged(Consumer<List<ToolSpec>> listener) {
        listeners.onToolsChanged(Objects.requireNonNull(listener, "listener"));
    }

    /** Receives the server's resource list each time it announces a change. */
    public void onResourcesChanged(Consumer<List<McpResourceInfo>> listener) {
        listeners.onResourcesChanged(Objects.requireNonNull(listener, "listener"));
    }

    /** Receives the server's prompt list each time it announces a change. */
    public void onPromptsChanged(Consumer<List<McpPromptInfo>> listener) {
        listeners.onPromptsChanged(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public synchronized void close() {
        McpSyncClient c = client;
        client = null;
        if (c != null) {
            c.closeGracefully();
        }
    }

    private McpSyncClient require(String feature, String capability, Predicate<McpSchema.ServerCapabilities> offered) {
        McpSyncClient c = client;
        if (c == null) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.MCP,
                    "connector not started; call start() first");
        }
        report.require(feature);
        if (!offered.test(capabilities)) {
            throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.MCP,
                    "server did not declare the '" + capability + "' capability");
        }
        return c;
    }

    private <T> T observe(String operation, String what, Supplier<T> action) {
        return ObservationHandler.observe(observations, new Observation(ProtocolId.MCP, operation, what), () -> {
            try {
                return action.get();
            } catch (RuntimeException e) {
                throw McpErrors.translate(e, operation);
            }
        });
    }

    /** The SDK coordinates, generated from {@code gradle.properties} at build time. */
    static Properties sdkProperties() {
        Properties p = new Properties();
        try (InputStream in = McpClientConnector.class.getResourceAsStream("agentweave-mcp.properties")) {
            if (in == null) {
                throw new IllegalStateException("agentweave-mcp.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** Settings shared by both builders. */
    private static final class Settings {
        private Duration requestTimeout = Duration.ofSeconds(20);
        private String clientName = "agentweave";
        private String clientVersion = "0.1.0";
        private CredentialProvider credentials = CredentialProvider.NONE;
        private ObservationHandler observations = ObservationHandler.NOOP;

        void requestTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.requestTimeout = timeout;
        }

        void clientInfo(String name, String version) {
            this.clientName = Objects.requireNonNull(name, "name");
            this.clientVersion = Objects.requireNonNull(version, "version");
        }
    }

    /** Configures an {@link McpClientConnector} over stdio. */
    public static final class Builder {

        private final String command;
        private final List<String> args;
        private final Map<String, String> environment = new HashMap<>();
        private final Settings settings = new Settings();

        private Builder(String command, List<String> args) {
            this.command = Objects.requireNonNull(command, "command");
            this.args = List.copyOf(args);
        }

        /** Adds an environment variable for the server process. */
        public Builder env(String name, String value) {
            environment.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Timeout for initialization and for each request. Default 20 seconds. */
        public Builder requestTimeout(Duration timeout) {
            settings.requestTimeout(timeout);
            return this;
        }

        /** The {@code clientInfo} sent during initialization. */
        public Builder clientInfo(String name, String version) {
            settings.clientInfo(name, version);
            return this;
        }

        /**
         * Credentials for the server, looked up with the command as target. Only
         * {@code NamedSecret}s apply; they become environment variables of the server process.
         */
        public Builder credentials(CredentialProvider provider) {
            settings.credentials = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public Builder observations(ObservationHandler handler) {
            settings.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public McpClientConnector build() {
            return new McpClientConnector(new ClientTransport.Stdio(command, args, environment), settings);
        }
    }

    /** Configures an {@link McpClientConnector} over Streamable HTTP. */
    public static final class HttpBuilder {

        private final String baseUrl;
        private String endpoint = "/mcp";
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Settings settings = new Settings();
        private io.agentweave.core.auth.HttpAuthorizer authorizer;
        private HttpBuilder(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            URI uri;
            try {
                uri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid base URL: " + baseUrl, e);
            }
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("base URL must be an absolute http(s) URL: " + baseUrl);
            }
            this.baseUrl = baseUrl;
        }

        /** The MCP endpoint path, resolved against the base URL. Default {@code /mcp}. */
        public HttpBuilder endpoint(String path) {
            this.endpoint = Objects.requireNonNull(path, "path");
            return this;
        }

        /** Adds a header sent with every request. Credentials with the same name take precedence. */
        public HttpBuilder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Timeout for connecting, initialization and each request. Default 20 seconds. */
        public HttpBuilder requestTimeout(Duration timeout) {
            settings.requestTimeout(timeout);
            return this;
        }

        /** The {@code clientInfo} sent during initialization. */
        public HttpBuilder clientInfo(String name, String version) {
            settings.clientInfo(name, version);
            return this;
        }

        /**
         * Credentials for the server, looked up with the base URL as target. A {@code BearerToken}
         * becomes {@code Authorization: Bearer <token>}; a {@code NamedSecret} becomes a header
         * named after the secret.
         */
        public HttpBuilder credentials(CredentialProvider provider) {
            settings.credentials = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public HttpBuilder observations(ObservationHandler handler) {
            settings.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Obtains tokens on demand, for example MCP authorization via agentweave-oauth's OAuthClient. */
        public HttpBuilder authorizer(io.agentweave.core.auth.HttpAuthorizer authorizer) {
            this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
            return this;
        }
        public McpClientConnector build() {
            return new McpClientConnector(new ClientTransport.Http(baseUrl, endpoint, headers, authorizer), settings);
        }
    }
}
