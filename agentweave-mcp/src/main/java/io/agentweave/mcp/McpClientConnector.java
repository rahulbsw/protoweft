package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.observe.ObservationHandler.Observation;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
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
 * <p>Thread-safe once started. See {@link #compliance()} for exactly what is implemented.
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
    private volatile boolean toolsOffered;

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
                McpFeatures.supported(transport.featureId()), McpFeatures.unsupported(transport.featureId()));
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
        McpClientTransport sdkTransport = transport.open(
                credentials.credentialsFor(ProtocolId.MCP, transport.credentialTarget()), requestTimeout);
        McpSyncClient sync = McpClient.sync(sdkTransport)
                .requestTimeout(requestTimeout)
                .initializationTimeout(requestTimeout)
                .clientInfo(McpSchema.Implementation.builder(clientName, clientVersion).build())
                .build();
        try {
            McpSchema.InitializeResult init = observe("initialize", target, sync::initialize);
            toolsOffered = init.capabilities() != null && init.capabilities().tools() != null;
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
        McpSyncClient c = requireTools(McpFeatures.TOOLS_LIST);
        McpSchema.ListToolsResult result = observe("tools/list", "", c::listTools);
        List<ToolSpec> tools = new ArrayList<>(result.tools().size());
        for (McpSchema.Tool t : result.tools()) {
            tools.add(new ToolSpec(t.name(), t.description(), t.inputSchema(), metadata(t)));
        }
        return List.copyOf(tools);
    }

    /** MCP tool annotations are hints; only the ones the server set are carried over. */
    private static Map<String, Object> metadata(McpSchema.Tool t) {
        Map<String, Object> meta = new HashMap<>();
        if (t.title() != null) {
            meta.put(ToolSpec.META_TITLE, t.title());
        }
        McpSchema.ToolAnnotations a = t.annotations();
        if (a != null) {
            if (a.title() != null && t.title() == null) {
                meta.put(ToolSpec.META_TITLE, a.title());
            }
            putIfSet(meta, ToolSpec.META_READ_ONLY, a.readOnlyHint());
            putIfSet(meta, ToolSpec.META_DESTRUCTIVE, a.destructiveHint());
            putIfSet(meta, ToolSpec.META_IDEMPOTENT, a.idempotentHint());
            putIfSet(meta, ToolSpec.META_OPEN_WORLD, a.openWorldHint());
        }
        return meta;
    }

    private static void putIfSet(Map<String, Object> meta, String key, Boolean value) {
        if (value != null) {
            meta.put(key, value);
        }
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        McpSyncClient c = requireTools(McpFeatures.TOOLS_CALL);
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(invocation.toolName())
                .arguments(invocation.arguments())
                .build();
        McpSchema.CallToolResult result = observe("tools/call", invocation.toolName(), () -> c.callTool(request));
        List<Content> content = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content part : result.content()) {
                content.add(toNeutral(part));
            }
        }
        return new ToolOutcome(content, Boolean.TRUE.equals(result.isError()), result.structuredContent());
    }

    @Override
    public synchronized void close() {
        McpSyncClient c = client;
        client = null;
        if (c != null) {
            c.closeGracefully();
        }
    }

    private McpSyncClient requireTools(String feature) {
        McpSyncClient c = client;
        if (c == null) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.MCP,
                    "connector not started; call start() first");
        }
        report.require(feature);
        if (!toolsOffered) {
            throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.MCP,
                    "server did not declare the 'tools' capability");
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

    private static Content toNeutral(McpSchema.Content part) {
        if (part instanceof McpSchema.TextContent text) {
            return new Content.Text(text.text());
        }
        if (part instanceof McpSchema.ImageContent image) {
            return new Content.Data(image.mimeType(), image.data());
        }
        if (part instanceof McpSchema.AudioContent audio) {
            return new Content.Data(audio.mimeType(), audio.data());
        }
        if (part instanceof McpSchema.ResourceLink link) {
            return new Content.Link(link.uri(), link.mimeType(), link.name());
        }
        return new Content.Unmapped(part.type());
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

        public McpClientConnector build() {
            return new McpClientConnector(new ClientTransport.Http(baseUrl, endpoint, headers), settings);
        }
    }
}
