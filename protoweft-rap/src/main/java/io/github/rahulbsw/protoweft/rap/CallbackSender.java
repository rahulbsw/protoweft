package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Posts callback messages to a runtime's {@code callback_url}, retrying 5xx, connection failures
 * and timeouts with exponential backoff and never 4xx (transport.md "Tool → Runtime").
 */
final class CallbackSender {

    private static final System.Logger LOG = System.getLogger(RapToolServer.class.getName());

    private final HttpClient client;
    private final Duration requestTimeout;
    private final RapRetryPolicy retry;
    private final HttpAuthorizer authorizer;
    private final Executor executor;

    CallbackSender(HttpClient client, Duration requestTimeout, RapRetryPolicy retry, HttpAuthorizer authorizer,
                   Executor executor) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.retry = retry;
        this.authorizer = authorizer;
        this.executor = executor;
    }

    /** Messages for one call, delivered one after another in submission order. */
    Queue queue(URI callbackUrl) {
        return new Queue(callbackUrl);
    }

    /** @return {@code true} if the runtime answered 2xx */
    boolean deliver(URI url, Map<String, Object> message) {
        byte[] body = Json.writeBytes(message);
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (authorizer != null) {
                authorizer.authorization(url).ifPresent(v -> b.header("Authorization", v));
            }
            int status;
            try {
                status = client.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (IOException e) {
                status = -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (status >= 200 && status < 300) {
                return true;
            }
            if (status >= 400 && status < 500) {
                LOG.log(System.Logger.Level.WARNING, "RAP runtime rejected a " + message.get("type")
                        + " callback with HTTP " + status + "; not retrying");
                return false;
            }
            if (attempt >= retry.maxAttempts()) {
                LOG.log(System.Logger.Level.WARNING, "giving up on a " + message.get("type") + " callback after "
                        + attempt + " attempts (last status " + status + ")");
                return false;
            }
            try {
                ClientHttp.sleep(retry.delayBefore(attempt));
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** A per-call FIFO drained on the delivery executor, so results precede events and events keep their order. */
    final class Queue {
        private final URI url;
        private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();

        private Queue(URI url) {
            this.url = url;
        }

        URI url() {
            return url;
        }

        /** Queues a message; the future tells whether it was delivered. */
        CompletableFuture<Boolean> send(Map<String, Object> message) {
            CompletableFuture<Boolean> delivered = new CompletableFuture<>();
            pending.add(() -> delivered.complete(deliver(url, message)));
            schedule();
            return delivered;
        }

        private void schedule() {
            if (draining.compareAndSet(false, true)) {
                executor.execute(this::drain);
            }
        }

        private void drain() {
            try {
                Runnable next;
                while ((next = pending.poll()) != null) {
                    next.run();
                }
            } finally {
                draining.set(false);
                if (!pending.isEmpty()) {
                    schedule();
                }
            }
        }
    }
}
