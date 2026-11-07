package io.github.rahulbsw.protoweft.rap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A scriptable RAP tool server on the JDK HTTP server, for testing the client. */
final class FakeToolServer implements AutoCloseable {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    final BlockingQueue<Map<String, Object>> invocations = new LinkedBlockingQueue<>();
    final BlockingQueue<Map<String, Object>> cancels = new LinkedBlockingQueue<>();
    final BlockingQueue<Map<String, Object>> closes = new LinkedBlockingQueue<>();
    final BlockingQueue<Map<String, Object>> choiceAnswers = new LinkedBlockingQueue<>();
    final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    final Deque<Integer> ackStatuses = new ArrayDeque<>();
    volatile String manifest;
    volatile int discoveryStatus = 200;
    volatile Consumer<Map<String, Object>> onInvoke = inv -> { };

    FakeToolServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        manifest = Json.write(Map.of("name", "fake", "endpoint", baseUrl() + "/invoke", "tools", List.of(
                Map.of("name", "echo", "description", "Echoes", "inputSchema", Map.of("type", "object"),
                        "annotations", Map.of("x-protoweft-readOnly", true, "x-acme", "1"),
                        "displayScript", "\"echo\""),
                Map.of("name", "watch", "description", "Watches", "inputSchema", Map.of("type", "object")))));
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Map<String, Object> nextInvocation() throws InterruptedException {
        return poll(invocations);
    }

    static Map<String, Object> poll(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
        Map<String, Object> m = queue.poll(10, TimeUnit.SECONDS);
        if (m == null) {
            throw new AssertionError("nothing arrived within 10 s");
        }
        return m;
    }

    /** POSTs JSON to a URL (a callback URL) and returns the status. */
    static int post(String url, Object json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json instanceof String s ? s : Json.write(json))).build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A tool_result for an invocation. */
    static Map<String, Object> result(Map<String, Object> inv, String text) {
        return Map.of("type", "tool_result", "group_id", inv.get("group_id"), "id", inv.get("id"), "text", text);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authorizationHeaders.add(auth);
            }
            if (ex.getRequestMethod().equals("GET") && path.equals("/.well-known/rap-toolset")) {
                byte[] body = manifest.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(discoveryStatus, body.length);
                ex.getResponseBody().write(body);
                return;
            }
            Map<String, Object> body = Json.parseObject(ex.getRequestBody().readAllBytes());
            switch (path) {
                case "/invoke" -> {
                    int status;
                    synchronized (ackStatuses) {
                        status = ackStatuses.isEmpty() ? 200 : ackStatuses.poll();
                    }
                    invocations.add(body);
                    ex.sendResponseHeaders(status, -1);
                    if (status == 200) {
                        executor.execute(() -> onInvoke.accept(body));
                    }
                    return;
                }
                case "/cancel_tool_call" -> cancels.add(body);
                case "/close_thread" -> closes.add(body);
                case "/user_choice_response" -> choiceAnswers.add(body);
                default -> {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
            }
            ex.sendResponseHeaders(200, -1);
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
