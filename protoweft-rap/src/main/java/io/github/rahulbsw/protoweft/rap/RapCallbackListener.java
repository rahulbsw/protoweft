package io.github.rahulbsw.protoweft.rap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A built-in HTTP listener for RAP callbacks, for applications without a Protoweft server (JDK
 * {@code com.sun.net.httpserver}, no extra dependencies). Hand it to every
 * {@link RapClientConnector}; close it when done.
 *
 * <pre>{@code
 * try (RapCallbackListener callbacks = RapCallbackListener.builder().port(0).start()) {
 *     RapClientConnector tools = RapClientConnector.builder(url).callbacks(callbacks).build();
 *     ...
 * }
 * }</pre>
 */
public final class RapCallbackListener implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final CallbackRouter router;
    private final String baseUrl;

    private RapCallbackListener(HttpServer server, ExecutorService executor, String publicBaseUrl) {
        this.server = server;
        this.executor = executor;
        InetSocketAddress bound = server.getAddress();
        String host = bound.getAddress().getHostAddress();
        host = host.contains(":") ? "[" + host + "]" : host;
        this.baseUrl = publicBaseUrl != null ? publicBaseUrl : "http://" + host + ":" + bound.getPort();
        this.router = new CallbackRouter(() -> baseUrl);
        server.createContext(CallbackRouter.PATH, this::handle);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The bound port. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** The base URL callbacks are addressed to (the configured public URL, else the bound address). */
    public String baseUrl() {
        return baseUrl;
    }

    CallbackRouter router() {
        return router;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = CallbackServlet.readLimited(exchange.getRequestBody());
            int status = body == null ? 413 : router.dispatch(exchange.getRequestURI().getRawPath(), body);
            exchange.sendResponseHeaders(status, -1);
        } finally {
            exchange.close();
        }
    }

    /** Configures a {@link RapCallbackListener}. */
    public static final class Builder {

        private String host = "127.0.0.1";
        private int port;
        private String publicBaseUrl;

        /** Interface to bind; default {@code 127.0.0.1}. */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /** Port to bind; default 0 (a free port). */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** The URL tool servers reach the listener at, when it differs from the bound address (proxy, NAT). */
        public Builder publicBaseUrl(String url) {
            this.publicBaseUrl = Objects.requireNonNull(url, "url");
            return this;
        }

        /** @throws ProtoweftException with {@link ErrorCategory#CONFIGURATION} if it cannot bind */
        public RapCallbackListener start() {
            AtomicInteger n = new AtomicInteger();
            ExecutorService executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "rap-callback-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
                server.setExecutor(executor);
                RapCallbackListener listener = new RapCallbackListener(server, executor, publicBaseUrl);
                server.start();
                return listener;
            } catch (IOException e) {
                executor.shutdownNow();
                throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP,
                        "cannot listen for RAP callbacks on " + host + ":" + port + ": " + e.getMessage(), e);
            }
        }
    }
}
