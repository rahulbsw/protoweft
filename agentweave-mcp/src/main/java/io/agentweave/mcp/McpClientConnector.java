package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.auth.Credential;
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
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * An MCP client {@link ToolConnector} over the stdio transport, built on the official MCP
 * Java SDK. It launches the server as a subprocess, negotiates the protocol revision, and
 * exposes the server's tools through the protocol-neutral core API.
 *
 * <pre>{@code
 * try (ToolConnector mcp = McpClientConnector.stdio("npx", "-y", "@modelcontextprotocol/server-everything").build()) {
 *     mcp.start();
 *     ToolOutcome out = mcp.invoke(ToolInvocation.of("echo", Map.of("message", "hi")));
 * }
 * }</pre>
 *
 * <p>Thread-safe once started. See {@link #compliance()} for exactly what is implemented.
 */
public final class McpClientConnector implements ToolConnector {

    private final String command;
    private final List<String> args;
    private final String target;
    private final Map<String, String> environment;
    private final Duration requestTimeout;
    private final String clientName;
    private final String clientVersion;
    private final CredentialProvider credentials;
    private final ObservationHandler observations;
    private final ComplianceReport baseReport;

    private volatile McpSyncClient client;
    private volatile ComplianceReport report;
    private volatile boolean toolsOffered;

    private McpClientConnector(Builder b) {
        this.command = b.command;
        this.args = b.args;
        // Only the executable: arguments may carry secrets and must not reach telemetry.
        this.target = b.command;
        this.environment = Map.copyOf(b.environment);
        this.requestTimeout = b.requestTimeout;
        this.clientName = b.clientName;
        this.clientVersion = b.clientVersion;
        this.credentials = b.credentials;
        this.observations = b.observations;
        // Wire revisions come from the SDK's stdio transport itself, so the report cannot
        // drift from what the SDK actually negotiates.
        List<String> revisions = new StdioClientTransport(
                ServerParameters.builder(b.command).build(), McpJsonDefaults.getMapper()).protocolVersions();
        Properties sdk = sdkProperties();
        this.baseReport = new ComplianceReport(ProtocolId.MCP, sdk.getProperty("mcp.sdk.name"),
                sdk.getProperty("mcp.sdk.version"), revisions, null, McpFeatures.SUPPORTED, McpFeatures.UNSUPPORTED);
        this.report = baseReport;
    }

    /** Starts building a connector that launches {@code command} as an stdio MCP server. */
    public static Builder stdio(String command, String... args) {
        return new Builder(command, List.of(args));
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
        Map<String, String> env = new HashMap<>(environment);
        env.putAll(credentialEnvironment());
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder(command).args(args).env(env).build(), McpJsonDefaults.getMapper());
        McpSyncClient sync = McpClient.sync(transport)
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
            tools.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        return List.copyOf(tools);
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

    /**
     * MCP stdio servers take credentials from their environment rather than the HTTP
     * authorization flow, so only named secrets (as environment variables) apply here.
     */
    private Map<String, String> credentialEnvironment() {
        Map<String, String> env = new HashMap<>();
        for (Credential credential : credentials.credentialsFor(ProtocolId.MCP, target)) {
            if (credential instanceof Credential.NamedSecret secret) {
                env.put(secret.name(), secret.value());
            } else {
                throw new AgentWeaveException(ErrorCategory.AUTHENTICATION, ProtocolId.MCP,
                        "MCP stdio transport only accepts NamedSecret credentials (passed as environment variables); got "
                                + credential.getClass().getSimpleName());
            }
        }
        return env;
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

    private static Properties sdkProperties() {
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

    /** Configures an {@link McpClientConnector}. */
    public static final class Builder {

        private final String command;
        private final List<String> args;
        private final Map<String, String> environment = new HashMap<>();
        private Duration requestTimeout = Duration.ofSeconds(20);
        private String clientName = "agentweave";
        private String clientVersion = "0.1.0";
        private CredentialProvider credentials = CredentialProvider.NONE;
        private ObservationHandler observations = ObservationHandler.NOOP;

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
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.requestTimeout = timeout;
            return this;
        }

        /** The {@code clientInfo} sent during initialization. */
        public Builder clientInfo(String name, String version) {
            this.clientName = Objects.requireNonNull(name, "name");
            this.clientVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder credentials(CredentialProvider provider) {
            this.credentials = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public Builder observations(ObservationHandler handler) {
            this.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public McpClientConnector build() {
            return new McpClientConnector(this);
        }

    }
}
