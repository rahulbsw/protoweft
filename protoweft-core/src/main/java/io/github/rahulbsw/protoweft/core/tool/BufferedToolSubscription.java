package io.github.rahulbsw.protoweft.core.tool;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * The {@link ToolSubscription} producers hand out: they {@link #emit} events, and end the stream
 * with a last event, {@link #complete()} or {@link #fail}. Events are buffered until the single
 * subscriber requests them and are delivered, in order, on the thread that emits or requests.
 *
 * <p>Thread-safe.
 */
public final class BufferedToolSubscription implements ToolSubscription {

    private final String id;
    private final Runnable onCancel;
    private final Object lock = new Object();
    private final ArrayDeque<ToolEvent> buffer = new ArrayDeque<>();

    private Flow.Subscriber<? super ToolEvent> subscriber;
    private long demand;
    private boolean active = true;
    private boolean terminalPending;
    private Throwable failure;
    private boolean done;
    private boolean subscriberCancelled;
    private boolean draining;
    private boolean missed;

    /**
     * @param id       the subscription's id
     * @param onCancel run once when the consumer cancels while the subscription is active (for
     *                 example to tell the tool to stop); exceptions are ignored
     */
    public BufferedToolSubscription(String id, Runnable onCancel) {
        this.id = Objects.requireNonNull(id, "id");
        this.onCancel = onCancel == null ? () -> { } : onCancel;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Flow.Publisher<ToolEvent> events() {
        return this::subscribe;
    }

    @Override
    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    /**
     * Adds an event; a {@linkplain ToolEvent#last() last} event ends the subscription.
     *
     * @return {@code false} if the subscription had already ended and the event was dropped
     */
    public boolean emit(ToolEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            if (!active) {
                return false;
            }
            buffer.add(event);
            if (event.last()) {
                active = false;
                terminalPending = true;
            }
        }
        drain();
        return true;
    }

    /** Ends the subscription without a last event; buffered events are still delivered. */
    public void complete() {
        end(null);
    }

    /** Ends the subscription with an error, delivered after any buffered events. */
    public void fail(Throwable error) {
        end(Objects.requireNonNull(error, "error"));
    }

    @Override
    public void cancel() {
        boolean wasActive;
        synchronized (lock) {
            wasActive = active;
            active = false;
            buffer.clear();
            terminalPending = true;
            failure = null;
        }
        if (wasActive) {
            try {
                onCancel.run();
            } catch (RuntimeException ignored) {
                // best effort by contract
            }
        }
        drain();
    }

    private void end(Throwable error) {
        synchronized (lock) {
            if (!active) {
                return;
            }
            active = false;
            terminalPending = true;
            failure = error;
        }
        drain();
    }

    private void subscribe(Flow.Subscriber<? super ToolEvent> s) {
        Objects.requireNonNull(s, "subscriber");
        synchronized (lock) {
            if (subscriber != null) {
                s.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                s.onError(new IllegalStateException("subscription " + id + " accepts a single subscriber"));
                return;
            }
            subscriber = s;
        }
        s.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                if (n <= 0) {
                    fail(new IllegalArgumentException("request must be positive: " + n));
                    return;
                }
                synchronized (lock) {
                    demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
                }
                drain();
            }

            @Override
            public void cancel() {
                synchronized (lock) {
                    subscriberCancelled = true;
                }
                BufferedToolSubscription.this.cancel();
            }
        });
        drain();
    }

    private void drain() {
        synchronized (lock) {
            if (draining) {
                missed = true;
                return;
            }
            draining = true;
        }
        while (true) {
            Flow.Subscriber<? super ToolEvent> s;
            ToolEvent next = null;
            boolean terminate = false;
            Throwable error = null;
            synchronized (lock) {
                s = subscriber;
                if (s == null || done || subscriberCancelled) {
                    if (s != null && subscriberCancelled) {
                        done = true;
                    }
                    draining = false;
                    return;
                }
                if (!buffer.isEmpty() && demand > 0) {
                    next = buffer.poll();
                    demand--;
                } else if (buffer.isEmpty() && terminalPending) {
                    done = true;
                    terminate = true;
                    error = failure;
                } else if (missed) {
                    missed = false;
                    continue;
                } else {
                    draining = false;
                    return;
                }
            }
            try {
                if (next != null) {
                    s.onNext(next);
                } else if (terminate) {
                    if (error != null) {
                        s.onError(error);
                    } else {
                        s.onComplete();
                    }
                }
            } catch (RuntimeException e) {
                synchronized (lock) {
                    subscriberCancelled = true;
                }
                cancel();
            }
        }
    }
}
