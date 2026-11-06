package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.oauth2.sdk.util.URLUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A one-shot HTTP listener on {@code 127.0.0.1} that receives the authorization response (RFC 8252
 * §7.3). Callbacks whose {@code state} is missing or does not match are discarded with 400.
 */
final class LoopbackReceiver implements AutoCloseable {

    static final String PATH = "/callback";

    private final HttpServer server;
    private final ExecutorService executor;
    private final CompletableFuture<URI> result = new CompletableFuture<>();
    private volatile String expectedState;

    private LoopbackReceiver(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** Binds {@code preferredPort}, falling back to an ephemeral port when it is 0 or taken. */
    static LoopbackReceiver start(int preferredPort, boolean allowFallback) {
        InetAddress loopback;
        try {
            loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(loopback, preferredPort), 0);
        } catch (IOException e) {
            if (!allowFallback || preferredPort == 0) {
                throw Uris.config("cannot bind loopback redirect port " + preferredPort + ": " + e.getMessage());
            }
            try {
                server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            } catch (IOException e2) {
                throw Uris.config("cannot bind a loopback redirect port: " + e2.getMessage());
            }
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "protoweft-oauth-redirect");
            t.setDaemon(true);
            return t;
        });
        LoopbackReceiver receiver = new LoopbackReceiver(server, executor);
        server.createContext(PATH, receiver::handle);
        server.setExecutor(executor);
        server.start();
        return receiver;
    }

    int port() {
        return server.getAddress().getPort();
    }

    URI redirectUri() {
        return URI.create("http://127.0.0.1:" + port() + PATH);
    }

    void expect(String state) {
        this.expectedState = state;
    }

    URI await(Duration timeout) {
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw Uris.auth("no authorization response within " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Uris.auth("interrupted waiting for the authorization response", e);
        } catch (ExecutionException e) {
            throw Uris.auth("authorization response failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method not allowed");
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, List<String>> params = URLUtils.parseParameters(query);
            List<String> state = params.get("state");
            String expected = expectedState;
            if (expected == null || state == null || state.size() != 1 || !expected.equals(state.get(0))) {
                respond(exchange, 400, "Authorization response rejected: state mismatch.");
                return;
            }
            URI uri = URI.create(redirectUri() + (query == null ? "" : "?" + query));
            boolean ok = !params.containsKey("error");
            respond(exchange, ok ? 200 : 400, ok ? "Authorization complete. You can close this window."
                    : "Authorization failed. You can close this window.");
            result.complete(uri);
        }
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("<!doctype html><title>Protoweft</title><p>" + message + "</p>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
