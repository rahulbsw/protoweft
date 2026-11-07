package io.github.rahulbsw.protoweft.rap;

import com.sun.net.httpserver.HttpServer;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Support for the env-gated interop tests against Hydro Infinity's reference implementations
 * (see {@code scripts/interop/rap.sh} and the "Interoperability" section of SPEC.md). Without the
 * {@code RAP_INTEROP_*} environment variables every interop test is skipped.
 */
final class HydroInterop {

    private HydroInterop() {
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return v;
    }

    static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * Appends {@code RESULT|<direction>|<server>|<check>|PASS|<evidence>} to
     * {@code $RAP_INTEROP_REPORT_DIR/<direction>.txt} (when set) and stdout. Called after a
     * check's assertions passed, so a failed check shows up as a failed JUnit test instead.
     */
    static void report(String direction, String server, String check, String evidence) {
        String line = "RESULT|" + direction + "|" + server + "|" + check + "|PASS|"
                + evidence.replace("\n", "\\n") + System.lineSeparator();
        System.out.print(line);
        String dir = System.getenv("RAP_INTEROP_REPORT_DIR");
        if (dir != null && !dir.isBlank()) {
            try {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, direction + ".txt"), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * A transparent HTTP proxy in front of a {@link RapCallbackListener}: it forwards every
     * callback a Hydro tool server POSTs and records the message type, the ids and our listener's
     * answer, so the tests can show exactly which RAP messages a server sent and how we answered
     * (including messages our public API never surfaces, such as {@code view_update} or events
     * after a cancellation).
     */
    static final class CallbackTap implements AutoCloseable {

        /** One callback, recorded on arrival; {@link #status()} is our listener's answer (-1 until it answered). */
        static final class Seen {
            private final String path;
            private final String type;
            private final Map<String, Object> body;
            private volatile int status = -1;

            Seen(String path, String type, Map<String, Object> body) {
                this.path = path;
                this.type = type;
                this.body = body;
            }

            String path() {
                return path;
            }

            String type() {
                return type;
            }

            Map<String, Object> body() {
                return body;
            }

            int status() {
                return status;
            }

            @Override
            public String toString() {
                return type + "->" + status;
            }
        }

        final List<Seen> seen = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "interop-tap");
            t.setDaemon(true);
            return t;
        });
        private final HttpClient http = HttpClient.newHttpClient();
        private volatile String target;

        private CallbackTap() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange; InputStream in = exchange.getRequestBody()) {
                    byte[] body = in.readAllBytes();
                    String path = exchange.getRequestURI().getRawPath();
                    String type = "?";
                    Map<String, Object> parsed = Map.of();
                    try {
                        parsed = Json.parseObject(body);
                        type = String.valueOf(parsed.get("type"));
                    } catch (IllegalArgumentException ignored) {
                        // recorded as type "?"; the listener answers it
                    }
                    // Recorded before forwarding: the client may complete a call while the
                    // listener is still answering, so arrival order is the order of this list.
                    Seen entry = new Seen(path, type, parsed);
                    seen.add(entry);
                    int status;
                    try {
                        HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create(target + path))
                                .timeout(Duration.ofSeconds(30))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                        String ct = exchange.getRequestHeaders().getFirst("Content-Type");
                        if (ct != null) {
                            forward.header("Content-Type", ct);
                        }
                        status = http.send(forward.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                    } catch (IOException | InterruptedException e) {
                        status = 502;
                    }
                    entry.status = status;
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            server.start();
        }

        static CallbackTap start() throws IOException {
            return new CallbackTap();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        CallbackTap forwardTo(RapCallbackListener listener) {
            target = "http://127.0.0.1:" + listener.port();
            return this;
        }

        /** Waits (up to 5 s) until every recorded callback has been answered. */
        CallbackTap settle() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (seen.stream().anyMatch(s -> s.status() < 0) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            return this;
        }

        List<String> types() {
            return seen.stream().map(Seen::type).toList();
        }

        List<Seen> since(int index) {
            return List.copyOf(seen.subList(Math.min(index, seen.size()), seen.size()));
        }

        /** Waits up to {@code timeout} for a callback of {@code type} at or after {@code index}. */
        boolean awaitType(int index, String type, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (since(index).stream().anyMatch(s -> s.type().equals(type))) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** Our client wired as in production (built-in listener on 127.0.0.1), with a {@link CallbackTap} in front. */
    static final class Client implements AutoCloseable {

        final CallbackTap tap;
        final RapCallbackListener listener;
        final RapClientConnector rap;

        Client(String baseUrl, UnaryOperator<RapClientConnector.Builder> config) throws IOException {
            tap = CallbackTap.start();
            listener = RapCallbackListener.builder().publicBaseUrl(tap.baseUrl()).start();
            tap.forwardTo(listener);
            rap = config.apply(RapClientConnector.builder(baseUrl).callbacks(listener)).build();
            rap.start();
        }

        @Override
        public void close() {
            rap.close();
            listener.close();
            tap.close();
        }
    }

    /** Collects a subscription's events, with waits long enough for Hydro's 10 s streaming interval. */
    static final class Events implements Flow.Subscriber<ToolEvent> {

        final List<ToolEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ToolEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        boolean awaitDone(Duration timeout) throws InterruptedException {
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<String> texts() {
            return events.stream().map(ToolEvent::text).toList();
        }
    }
}
