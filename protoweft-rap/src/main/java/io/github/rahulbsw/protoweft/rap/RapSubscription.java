package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.Content;

import java.util.List;
import java.util.Optional;

/**
 * The server side of a RAP subscription: sends {@code subscription_event}s for one tool call to
 * the runtime that started it. Obtained from {@link RapCallContext#subscribe()} while the tool
 * runs, or from {@link RapToolServer#recoveredSubscriptions()} after a restart. Events emitted
 * before the call's result is delivered are held and sent right after it. Thread-safe.
 */
public interface RapSubscription {

    String operation();

    /** The {@code group_id} of the subscribing thread. */
    String threadId();

    /** The {@code id} of the call that started the subscription. */
    String toolCallId();

    /** The authenticated caller's name. */
    String caller();

    /** The invocation's {@code user_id}, if any. */
    Optional<String> userId();

    /** Sends a non-associative event. Returns {@code false} if the subscription has ended. */
    default boolean emit(String text) {
        return emit(text, false);
    }

    /**
     * Sends an event.
     *
     * @param associative {@code true} asks the runtime to add it inline to the subscribing thread
     *                    rather than processing it in a child thread
     * @return {@code false} if the subscription has ended (cancelled, closed or its thread closed)
     */
    boolean emit(String text, boolean associative);

    /** Sends content as an event; RAP events are text, so non-text parts become text placeholders. */
    boolean emit(List<Content> content, boolean associative);

    /** Ends the subscription with a final event carrying {@code text}. */
    boolean close(String text, boolean associative);

    /** Ends the subscription with an empty final event. */
    default boolean close() {
        return close("", false);
    }

    /** {@code false} once closed, cancelled by the runtime, or ended by thread closure. */
    boolean isActive();

    /** Runs {@code action} when the runtime cancels the subscription or closes its thread (now, if it already has). */
    void onCancel(Runnable action);
}
