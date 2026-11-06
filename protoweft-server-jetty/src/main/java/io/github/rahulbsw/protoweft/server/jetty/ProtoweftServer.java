package io.github.rahulbsw.protoweft.server.jetty;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.FilterMapping;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.ee11.servlet.ServletMapping;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Hosts Protoweft server endpoints (MCP, A2A, UTCP) in an embedded Jetty server, for
 * applications that don't run inside a servlet container or Spring Boot.
 *
 * <pre>{@code
 * try (ProtoweftServer server = ProtoweftServer.builder()
 *         .port(8080)
 *         .endpoints(McpToolServer.http(tools))
 *         .authenticator(myAuthenticator)
 *         .start()) {
 *     server.join();
 * }
 * }</pre>
 */
public final class ProtoweftServer implements AutoCloseable {

    private final Server jetty;
    private final ServerConnector connector;
    private final String host;

    private ProtoweftServer(Server jetty, ServerConnector connector, String host) {
        this.jetty = jetty;
        this.connector = connector;
        this.host = host;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The bound port (useful with port 0). */
    public int port() {
        return connector.getLocalPort();
    }

    /** {@code http://<host>:<port>} with no trailing slash. */
    public String baseUrl() {
        String h = host == null || host.isEmpty() || host.equals("0.0.0.0") ? "localhost" : host;
        return "http://" + h + ":" + port();
    }

    /** Blocks until the server stops. */
    public void join() throws InterruptedException {
        jetty.join();
    }

    @Override
    public void close() {
        try {
            jetty.stop();
        } catch (Exception e) {
            throw new ProtoweftException(ErrorCategory.INTERNAL, null, "failed to stop server", e);
        }
    }

    /** Configures and starts an {@link ProtoweftServer}. */
    public static final class Builder {

        private String host = "localhost";
        private int port = 8080;
        private InboundAuthenticator authenticator = InboundAuthenticator.ALLOW_ALL;
        private final List<ServletEndpoint> endpoints = new ArrayList<>();

        /** Interface to bind; default {@code localhost}. Use {@code 0.0.0.0} for all interfaces. */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /** Port to bind; default 8080; {@code 0} picks a free port. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Called before every endpoint request. Default: allow everyone as anonymous. */
        public Builder authenticator(InboundAuthenticator authenticator) {
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
            return this;
        }

        public Builder endpoints(ServerEndpointProvider... providers) {
            for (ServerEndpointProvider provider : providers) {
                endpoints.addAll(provider.endpoints());
            }
            return this;
        }

        public Builder endpoint(ServletEndpoint endpoint) {
            endpoints.add(Objects.requireNonNull(endpoint, "endpoint"));
            return this;
        }

        /**
         * Starts the server.
         *
         * @throws ProtoweftException if it cannot bind or an endpoint name is duplicated
         */
        public ProtoweftServer start() {
            Server jetty = new Server();
            ServerConnector connector = new ServerConnector(jetty);
            connector.setHost(host);
            connector.setPort(port);
            jetty.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            context.setContextPath("/");
            ServletHandler handler = context.getServletHandler();
            Set<String> names = new HashSet<>();
            for (ServletEndpoint endpoint : endpoints) {
                if (!names.add(endpoint.name())) {
                    throw new ProtoweftException(ErrorCategory.CONFIGURATION, endpoint.protocol(),
                            "duplicate endpoint name: " + endpoint.name());
                }
                mount(handler, endpoint, authenticator);
            }
            jetty.setHandler(context);
            try {
                jetty.start();
            } catch (Exception e) {
                try {
                    jetty.stop();
                } catch (Exception ignored) {
                    // already failing
                }
                throw new ProtoweftException(ErrorCategory.CONFIGURATION, null,
                        "failed to start server on " + host + ":" + port + ": " + e.getMessage(), e);
            }
            return new ProtoweftServer(jetty, connector, host);
        }

        private static void mount(ServletHandler handler, ServletEndpoint endpoint, InboundAuthenticator auth) {
            String[] patterns = endpoint.urlPatterns().toArray(String[]::new);

            ServletHolder servlet = new ServletHolder(endpoint.name(), endpoint.servlet());
            servlet.setAsyncSupported(endpoint.asyncSupported());
            handler.addServlet(servlet);
            ServletMapping servletMapping = new ServletMapping();
            servletMapping.setServletName(endpoint.name());
            servletMapping.setPathSpecs(patterns);
            handler.addServletMapping(servletMapping);

            FilterHolder filter = new FilterHolder(new InboundAuthFilter(auth, endpoint.protocol()));
            filter.setName(endpoint.name() + "-auth");
            filter.setAsyncSupported(endpoint.asyncSupported());
            handler.addFilter(filter);
            FilterMapping filterMapping = new FilterMapping();
            filterMapping.setFilterName(filter.getName());
            filterMapping.setPathSpecs(patterns);
            filterMapping.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
            handler.addFilterMapping(filterMapping);
        }
    }
}
