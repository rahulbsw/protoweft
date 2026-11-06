package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
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
 * Serves any {@link ToolSource} (for example {@link io.github.rahulbsw.protoweft.core.tool.LocalTools} or a
 * {@link io.github.rahulbsw.protoweft.core.tool.ToolRegistry}) as an MCP server, built on the official MCP
 * Java SDK, over Streamable HTTP or stdio.
 *
 * <pre>{@code
 * // Streamable HTTP, hosted by any Protoweft host (embedded Jetty shown)
 * ProtoweftServer.builder().endpoints(McpToolServer.http(tools).serverInfo("weather", "1.0")).start();
 *
 * // stdio: serves until stdin closes
 * McpToolServer.stdio(tools).serverInfo("weather", "1.0").run();
 *
 * // with resources and prompts
 * McpToolServer.http(tools).resources(McpResources.scan(new Docs())).prompts(McpPrompts.scan(new Prompts()));
 * }</pre>
 *
 * <p>Tools reporting through {@link io.github.rahulbsw.protoweft.core.tool.ToolProgress} send MCP progress and
 * log notifications to the calling session. The tool list is read once, when serving starts.
 * See {@code compliance()} on each variant for exactly what is implemented.
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
        private String name = "protoweft";
        private String version = "0.1.0";
        private ObservationHandler observations = ObservationHandler.NOOP;
        private Duration requestTimeout = Duration.ofSeconds(60);
        private McpResources resources;
        private McpPrompts prompts;

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
            return new ToolServerCore(tools, name, version, observations, requestTimeout, resources, prompts);
        }
    }

    /**
     * An MCP server over Streamable HTTP. Hand it to a host (for example
     * {@code ProtoweftServer.builder().endpoints(...)}); the host's
     * {@link io.github.rahulbsw.protoweft.core.server.InboundAuthenticator} runs before every request and the
     * authenticated caller reaches the tools. Configure it before the host calls
     * {@link #endpoints()}.
     */
    public static final class Http implements ServerEndpointProvider, AutoCloseable {

        /** Read once: building a provider starts its session sweeper, so it is closed right away. */
        private static final List<String> HTTP_REVISIONS = httpRevisions();

        private final Settings settings;
        private HttpServletStreamableServerTransportProvider transport;
        private SweeperCapture.Captured<HttpServletStreamableServerTransportProvider> built;
        private ToolServerCore.Built server;
        private String path = "/mcp";
        private final List<String> allowedOrigins = new ArrayList<>();
        private List<ServletEndpoint> endpoints;

        private Http(ToolSource tools) {
            this.settings = new Settings(tools);
        }

        /** The {@code serverInfo} returned during initialization. Default {@code protoweft 0.1.0}. */
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

        /**
         * The longest a {@code tools/call}, {@code resources/read}, {@code prompts/get} or
         * {@code completion/complete} may run before failing with a JSON-RPC error. Default 60 seconds.
         */
        public synchronized Http requestTimeout(Duration timeout) {
            requireConfigurable();
            settings.requestTimeout(timeout);
            return this;
        }

        /** Publishes resources, declaring the {@code resources} capability (with {@code subscribe}). */
        public synchronized Http resources(McpResources resources) {
            requireConfigurable();
            settings.resources = Objects.requireNonNull(resources, "resources");
            return this;
        }

        /** Publishes prompts, declaring the {@code prompts} capability. */
        public synchronized Http prompts(McpPrompts prompts) {
            requireConfigurable();
            settings.prompts = Objects.requireNonNull(prompts, "prompts");
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
            return ToolServerCore.compliance(McpFeatures.TRANSPORT_STREAMABLE_HTTP, HTTP_REVISIONS);
        }

        /**
         * Closes all MCP sessions and stops the SDK's session sweeper thread. Hosts also
         * trigger this when they destroy the endpoint's servlet. Safe to call more than once.
         */
        @Override
        public synchronized void close() {
            if (server != null) {
                server.unbind().run();
            }
            if (transport != null) {
                try {
                    transport.closeGracefully().block();
                } finally {
                    built.shutdownSweepers();
                    transport = null;
                }
            }
        }

        private static List<String> httpRevisions() {
            var probe = SweeperCapture.build(() -> HttpServletStreamableServerTransportProvider.builder().build());
            try {
                return List.copyOf(probe.value().protocolVersions());
            } finally {
                probe.value().closeGracefully().block();
                probe.shutdownSweepers();
            }
        }

        /**
         * The single async endpoint, named {@code mcp} for the default path. The first call
         * builds the server and reads the tool list.
         *
         * @throws ProtoweftException with {@code CONFIGURATION} if a tool can't be served over MCP
         */
        @Override
        public synchronized List<ServletEndpoint> endpoints() {
            if (endpoints == null) {
                built = SweeperCapture.build(() -> HttpServletStreamableServerTransportProvider
                        .builder()
                        .mcpEndpoint(path)
                        .contextExtractor(request -> McpTransportContext.create(
                                Map.of(ToolServerCore.CALLER_KEY, InboundAuthFilter.caller(request))))
                        .httpHeaderValidator(DefaultServerTransportSecurityValidator.builder()
                                .allowedOrigins(List.copyOf(allowedOrigins)).build())
                        .build());
                transport = built.value();
                server = settings.core().build(McpServer.async(transport));
                String name = "/mcp".equals(path) ? "mcp" : "mcp:" + path;
                endpoints = List.of(new ServletEndpoint(name, ProtocolId.MCP, List.of(path),
                        new ClosingServlet(transport, this::close), true));
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

        /** The {@code serverInfo} returned during initialization. Default {@code protoweft 0.1.0}. */
        public Stdio serverInfo(String name, String version) {
            settings.serverInfo(name, version);
            return this;
        }

        /** Observes each {@code tools/call}. */
        public Stdio observations(ObservationHandler handler) {
            settings.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * The longest a {@code tools/call}, {@code resources/read}, {@code prompts/get} or
         * {@code completion/complete} may run before failing with a JSON-RPC error. Default 60 seconds.
         */
        public Stdio requestTimeout(Duration timeout) {
            settings.requestTimeout(timeout);
            return this;
        }

        /** Publishes resources, declaring the {@code resources} capability (with {@code subscribe}). */
        public Stdio resources(McpResources resources) {
            settings.resources = Objects.requireNonNull(resources, "resources");
            return this;
        }

        /** Publishes prompts, declaring the {@code prompts} capability. */
        public Stdio prompts(McpPrompts prompts) {
            settings.prompts = Objects.requireNonNull(prompts, "prompts");
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
         * @throws ProtoweftException with {@code CONFIGURATION} if a tool can't be served over MCP
         */
        public void run() {
            CountDownLatch closed = new CountDownLatch(1);
            InputStream in = new EofSignallingInputStream(System.in, closed);
            PrintStream out = System.out;
            System.setOut(System.err);
            ToolServerCore.Built server = null;
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
