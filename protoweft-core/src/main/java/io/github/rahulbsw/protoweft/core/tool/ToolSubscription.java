package io.github.rahulbsw.protoweft.core.tool;

import java.util.concurrent.Flow;

/**
 * A tool call that keeps delivering {@link ToolEvent}s after its first result, carried by
 * {@link ToolOutcome#subscription()}. Protocols that support it (RAP subscriptions) fill it in;
 * everywhere else outcomes have none.
 *
 * <p>Thread-safe. Producers use {@link BufferedToolSubscription}.
 */
public interface ToolSubscription {

    /** The protocol's identifier for the subscription, for example the originating tool call id. */
    String id();

    /**
     * The events. Accepts exactly one subscriber (a second gets {@code onError}); events that
     * arrive before it subscribes are buffered. The stream completes after a {@linkplain
     * ToolEvent#last() last} event or {@link #cancel()}, and fails if the subscription is lost.
     * Cancelling the {@link Flow.Subscription} cancels this subscription.
     */
    Flow.Publisher<ToolEvent> events();

    /** Stops the subscription: tells the tool (best effort) and completes {@link #events()}. Idempotent. */
    void cancel();

    /** {@code true} until a last event, a cancel or a failure. */
    boolean isActive();
}
