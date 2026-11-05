package io.agentweave.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Serves protected resource metadata documents at chosen paths, recording every request. */
final class TestResourceServer implements AutoCloseable {

    final HttpServer server;
    final Map<String, String> documents = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();

    TestResourceServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    URI uri(String path) {
        return URI.create(origin() + path);
    }

    /** Publishes PRM for {@code resourcePath} at {@code atPath}. */
    TestResourceServer publish(String atPath, String resourcePath, TestAuthorizationServer as, String... scopes) {
        ProtectedResourceMetadata.Builder b = ProtectedResourceMetadata.builder(uri(resourcePath))
                .authorizationServer(URI.create(as.issuer()));
        if (scopes.length > 0) {
            b.scopesSupported(scopes);
        }
        documents.put(atPath, b.build().toJson());
        return this;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            requests.add(path);
            String doc = documents.get(path);
            if (doc == null) {
                TestAuthorizationServer.send(ex, 404, "application/json", "{\"error\":\"not_found\"}");
            } else {
                TestAuthorizationServer.send(ex, 200, "application/json", doc);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
