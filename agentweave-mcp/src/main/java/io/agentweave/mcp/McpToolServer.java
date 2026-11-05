package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.server.InboundAuthFilter;
import io.agentweave.server.ServerEndpointProvider;
import io.agentweave.server.ServletEndpoint;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Serves any {@link ToolSource} (for example {@link io.agentweave.core.tool.LocalTools} or a
 * {@link io.agentweave.core.tool.ToolRegistry}) as an MCP server, built on the official MCP
 * Java SDK, over Streamable HTTP or stdio.
 *
 * <pre>{@code
 * // Streamable HTTP, hosted by any AgentWeave host (embedded Jetty shown)
 * AgentWeaveServer.builder().endpoints(McpToolServer.http(tools).serverInfo("weather", "1.0")).start();
 *
 * // stdio: serves until stdin closes
 * McpToolServer.stdio(tools).serverInfo("weather", "1.0").run();
 * }</pre>
 *
 * <p>The tool list is read once, when serving starts. See {@code compliance()} on each variant
 * for exactly what is implemented.
 */
public final class McpToolServer {

    private McpToolServer() {
    }

    /** An MCP server for {@code tools} over Streamable HTTP, mounted by a servlet host. */
    public static Http http(ToolSource tools) {
        return new Http(tools);
    }

    /** An MCP server for {@code tools} over stdio. */
    public static Stdio stdio(ToolSource tools) {
        return new Stdio(tools);
    }

    /** Configuration shared by both transports. */
    private static final class Settings {
        private final ToolSource tools;
        private String name = "agentweave";
        private String version = "0.1.0";
        private ObservationHandler observations = ObservationHandler.NOOP;
        private Duration requestTimeout = Duration.ofSeconds(60);

        Settings(ToolSource tools) {
            this.tools = Objects.requireNonNull(tools, "tools");
        }

        void serverInfo(String name, String version) {
            this.name = Objects.requireNonNull(name, "name");
            this.version = Objects.requireNonNull(version, "version");
        }

        void requestTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.requestTimeout = timeout;
        }

        ToolServerCore core() {
            return new ToolServerCore(tools, name, version, observations, requestTimeout);
        }
    }

    /**
     * An MCP server over Streamable HTTP. Hand it to a host (for example
     * {@code AgentWeaveServer.builder().endpoints(...)}); the host's
     * {@link io.agentweave.core.server.InboundAuthenticator} runs before every request and the
     * authenticated caller reaches the tools. Configure it before the host calls
     * {@link #endpoints()}.
     */
    public static final class Http implements ServerEndpointProvider {

        private final Settings settings;
        private String path = "/mcp";
        private final List<String> allowedOrigins = new ArrayList<>();
        private List<ServletEndpoint> endpoints;

        private Http(ToolSource tools) {
            this.settings = new Settings(tools);
        }

        /** The {@code serverInfo} returned during initialization. Default {@code agentweave 0.1.0}. */
        public synchronized Http serverInfo(String name, String version) {
            requireConfigurable();
            settings.serverInfo(name, version);
            return this;
        }

        /** Observes each {@code tools/call}. */
        public synchronized Http observations(ObservationHandler handler) {
            requireConfigurable();
            settings.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** The longest a {@code tools/call} may run before failing with a JSON-RPC error. Default 60 seconds. */
        public synchronized Http requestTimeout(Duration timeout) {
            requireConfigurable();
            settings.requestTimeout(timeout);
            return this;
        }

        /** The endpoint path, for example {@code /mcp} (the default). */
        public synchronized Http endpointPath(String path) {
            requireConfigurable();
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/") || path.contains("*")) {
                throw new IllegalArgumentException("path must start with '/' and contain no wildcard: " + path);
            }
            this.path = path;
            return this;
        }

        /**
         * Browser origins allowed to call the endpoint, for example {@code https://app.example.com}
         * ({@code :*} matches any port). Requests carrying any other {@code Origin} header are
         * rejected with 403, as MCP requires against DNS rebinding. Default: none.
         */
        public synchronized Http allowedOrigins(String... origins) {
            requireConfigurable();
            allowedOrigins.addAll(List.of(origins));
            return this;
        }

        /** What this server implements; the revision is negotiated per session, so none is set. */
        public ComplianceReport compliance() {
            return ToolServerCore.compliance(McpFeatures.TRANSPORT_STREAMABLE_HTTP,
                    HttpServletStreamableServerTransportProvider.builder().build().protocolVersions());
        }

        /**
         * The single async endpoint, named {@code mcp} for the default path. The first call
         * builds the server and reads the tool list.
         *
         * @throws AgentWeaveException with {@code CONFIGURATION} if a tool can't be served over MCP
         */
        @Override
        public synchronized List<ServletEndpoint> endpoints() {
            if (endpoints == null) {
                HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
                        .builder()
                        .mcpEndpoint(path)
                        .contextExtractor(request -> McpTransportContext.create(
                                Map.of(ToolServerCore.CALLER_KEY, InboundAuthFilter.caller(request))))
                        .httpHeaderValidator(DefaultServerTransportSecurityValidator.builder()
                                .allowedOrigins(List.copyOf(allowedOrigins)).build())
                        .build();
                settings.core().build(McpServer.async(transport));
                String name = "/mcp".equals(path) ? "mcp" : "mcp:" + path;
                endpoints = List.of(new ServletEndpoint(name, ProtocolId.MCP, List.of(path), transport, true));
            }
            return endpoints;
        }

        private void requireConfigurable() {
            if (endpoints != null) {
                throw new IllegalStateException("server already built; configure it before endpoints() is called");
            }
        }
    }

    /**
     * An MCP server over stdio: JSON-RPC on stdin/stdout. Anything else written to stdout
     * corrupts the protocol, so applications must log to stderr.
     */
    public static final class Stdio {

        private final Settings settings;

        private Stdio(ToolSource tools) {
            this.settings = new Settings(tools);
        }

        /** The {@code serverInfo} returned during initialization. Default {@code agentweave 0.1.0}. */
        public Stdio serverInfo(String name, String version) {
            settings.serverInfo(name, version);
            return this;
        }

        /** Observes each {@code tools/call}. */
        public Stdio observations(ObservationHandler handler) {
            settings.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** The longest a {@code tools/call} may run before failing with a JSON-RPC error. Default 60 seconds. */
        public Stdio requestTimeout(Duration timeout) {
            settings.requestTimeout(timeout);
            return this;
        }

        /** What this server implements; the revision is negotiated with the client, so none is set. */
        public ComplianceReport compliance() {
            return ToolServerCore.compliance(McpFeatures.TRANSPORT_STDIO, new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(), InputStream.nullInputStream(), OutputStream.nullOutputStream())
                    .protocolVersions());
        }

        /**
         * Serves on {@code System.in}/{@code System.out}, blocking until stdin closes. While
         * serving, {@code System.out} is redirected to {@code System.err} so stray prints can't
         * corrupt the protocol.
         *
         * @throws AgentWeaveException with {@code CONFIGURATION} if a tool can't be served over MCP
         */
        public void run() {
            CountDownLatch closed = new CountDownLatch(1);
            InputStream in = new EofSignallingInputStream(System.in, closed);
            PrintStream out = System.out;
            System.setOut(System.err);
            McpAsyncServer server = null;
            try {
                server = settings.core().build(McpServer.async(
                        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out)));
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (server != null) {
                    server.close();
                }
                System.setOut(out);
            }
        }
    }

    /** Counts down when the wrapped stream reaches end of input or fails. */
    private static final class EofSignallingInputStream extends FilterInputStream {

        private final CountDownLatch closed;

        EofSignallingInputStream(InputStream in, CountDownLatch closed) {
            super(in);
            this.closed = closed;
        }

        @Override
        public int read() throws IOException {
            return signal(() -> super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return signal(() -> super.read(b, off, len));
        }

        private int signal(IoRead read) throws IOException {
            try {
                int n = read.get();
                if (n < 0) {
                    closed.countDown();
                }
                return n;
            } catch (IOException | RuntimeException e) {
                closed.countDown();
                throw e;
            }
        }

        private interface IoRead {
            int get() throws IOException;
        }
    }
}
