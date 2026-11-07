package io.github.rahulbsw.protoweft.rap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A minimal RAP runtime for testing the server: records callbacks and sends raw requests. */
final class FakeRuntime implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    final BlockingQueue<Map<String, Object>> callbacks = new LinkedBlockingQueue<>();
    final AtomicInteger requests = new AtomicInteger();
    final Deque<Integer> statuses = new ArrayDeque<>();

    FakeRuntime() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/cb/", this::handle);
        server.start();
    }

    String callbackUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/cb/1";
    }

    Map<String, Object> next() throws InterruptedException {
        return FakeToolServer.poll(callbacks);
    }

    Map<String, Object> nextWithin(long millis) throws InterruptedException {
        return callbacks.poll(millis, TimeUnit.MILLISECONDS);
    }

    /** An invocation body for {@code operation}. */
    Map<String, Object> invocation(String operation, String id, Map<String, Object> arguments) {
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("operation", operation);
        inv.put("arguments", arguments);
        inv.put("id", id);
        inv.put("call_id", null);
        inv.put("callback_url", callbackUrl());
        inv.put("group_id", "thread-1");
        return inv;
    }

    static Response send(String method, String url, Object json, String bearer) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            if (bearer != null) {
                b.header("Authorization", "Bearer " + bearer);
            }
            if (json == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json instanceof String s ? s : Json.write(json)));
            }
            HttpResponse<String> r = FakeToolServer.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    record Response(int status, String body) {
        Map<String, Object> json() {
            return Json.parseObject(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools() {
            return (List<Map<String, Object>>) json().get("tools");
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            requests.incrementAndGet();
            int status;
            synchronized (statuses) {
                status = statuses.isEmpty() ? 200 : statuses.poll();
            }
            byte[] body = ex.getRequestBody().readAllBytes();
            if (status == 200) {
                callbacks.add(Json.parseObject(body));
            }
            ex.sendResponseHeaders(status, -1);
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
