package io.agentweave.spring.boot;

import io.agentweave.core.observe.ObservationHandler;

import java.time.Duration;
import java.util.List;

/** Fans each observation out to every {@link ObservationHandler} bean; one failing handler doesn't stop the others. */
final class CompositeObservationHandler implements ObservationHandler {

    private final List<ObservationHandler> handlers;

    private CompositeObservationHandler(List<ObservationHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    static ObservationHandler of(List<ObservationHandler> handlers) {
        return switch (handlers.size()) {
            case 0 -> NOOP;
            case 1 -> handlers.get(0);
            default -> new CompositeObservationHandler(handlers);
        };
    }

    @Override
    public void onStart(Observation observation) {
        each(h -> h.onStart(observation));
    }

    @Override
    public void onSuccess(Observation observation, Duration elapsed) {
        each(h -> h.onSuccess(observation, elapsed));
    }

    @Override
    public void onError(Observation observation, Duration elapsed, Throwable error) {
        each(h -> h.onError(observation, elapsed, error));
    }

    private void each(java.util.function.Consumer<ObservationHandler> call) {
        for (ObservationHandler h : handlers) {
            try {
                call.accept(h);
            } catch (RuntimeException ignored) {
                // Telemetry must never break a call (ObservationHandler contract).
            }
        }
    }
}
