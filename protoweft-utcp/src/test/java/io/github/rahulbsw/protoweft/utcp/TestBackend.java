package io.github.rahulbsw.protoweft.utcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A loopback HTTP backend (JDK HttpServer) that records every request and answers per path. */
final class TestBackend implements AutoCloseable {

    record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> v = headers.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    record Reply(int status, String contentType, String body, Map<String, String> headers) {
        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, Map.of());
        }

        static Reply text(int status, String body) {
            return new Reply(status, "text/plain; charset=utf-8", body, Map.of());
        }

        static Reply redirect(int status, String location) {
            return new Reply(status, null, "", Map.of("Location", location));
        }
    }

    @FunctionalInterface
    interface Handler {
        Reply handle(Recorded request);
    }

    final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final HttpServer server;
    private boolean closed;

    TestBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    TestBackend route(String path, Handler handler) {
        routes.put(path, handler);
        return this;
    }

    /** {@code http://127.0.0.1:<port><path>}. */
    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    int port() {
        return server.getAddress().getPort();
    }

    Recorded last() {
        return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
        Recorded req = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), headers,
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requests.add(req);
        Handler handler = routes.get(req.path());
        Reply reply = handler == null ? Reply.json(404, "{\"error\":\"no route\"}") : handler.handle(req);
        if (reply.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        }
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            server.stop(0);
        }
    }
}
