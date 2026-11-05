package io.agentweave.core.observe;

import io.agentweave.core.ProtocolId;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Observability hook fired around connector operations. Bind metrics, tracing, or logging
 * here without the core depending on any telemetry library.
 *
 * <p>Handlers run on the calling thread and must not throw; an exception from a handler is
 * swallowed so that telemetry can never break a call.
 */
public interface ObservationHandler {

    /** A handler that does nothing. */
    ObservationHandler NOOP = new ObservationHandler() { };

    /**
     * Identifies one operation.
     *
     * @param protocol  the protocol performing it
     * @param operation a stable operation name, for example {@code "tools/call"}
     * @param target    what it acts on, for example a tool name; may be empty
     */
    record Observation(ProtocolId protocol, String operation, String target) {
        public Observation {
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(operation, "operation");
            target = target == null ? "" : target;
        }
    }

    default void onStart(Observation observation) { }

    default void onSuccess(Observation observation, Duration elapsed) { }

    default void onError(Observation observation, Duration elapsed, Throwable error) { }

    /**
     * Runs {@code action}, reporting it to {@code handler}. Connectors use this so every
     * protocol reports operations the same way.
     */
    static <T> T observe(ObservationHandler handler, Observation observation, Supplier<T> action) {
        long start = System.nanoTime();
        safely(() -> handler.onStart(observation));
        try {
            T result = action.get();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            safely(() -> handler.onSuccess(observation, elapsed));
            return result;
        } catch (RuntimeException e) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            safely(() -> handler.onError(observation, elapsed, e));
            throw e;
        }
    }

    private static void safely(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never break a call.
        }
    }
}
