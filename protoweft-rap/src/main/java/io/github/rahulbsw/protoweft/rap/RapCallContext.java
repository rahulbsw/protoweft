package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolContext;
import io.github.rahulbsw.protoweft.core.tool.ToolContexts;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What a tool served by {@link RapToolServer} can do beyond returning a result: start a
 * subscription, ask the user to authorize access or to choose, and react to cancellation.
 *
 * <p>An {@code @AgentTool} method receives it by declaring a {@code RapCallContext} parameter; a
 * programmatic tool uses a {@code ContextualToolHandler} and {@link #from(ToolContexts)}. When the
 * tool is called over another protocol it gets a stand-in whose {@link #available()} is
 * {@code false} and whose other methods throw {@code ProtoweftException} with {@code UNSUPPORTED}.
 */
public interface RapCallContext extends ToolContext {

    /** The call's RAP context, or the unavailable stand-in. */
    static RapCallContext from(ToolContexts contexts) {
        return contexts.resolve(RapCallContext.class);
    }

    /** The tool name as invoked. */
    String operation();

    /** The invocation's {@code group_id}. */
    String threadId();

    /** The invocation's {@code id}. */
    String toolCallId();

    /** The invocation's {@code user_id}: the end user as the runtime claims it, if sent. */
    Optional<String> userId();

    /** The invocation's {@code thread_ancestors}, root first; empty for a root thread. */
    List<String> threadAncestors();

    /**
     * Makes this call a subscription: the result is delivered with {@code subscription: true} and
     * the returned handle keeps sending events afterwards, also after a restart when the server
     * has a durable {@link RapStore}. Calling it again returns the same handle.
     */
    RapSubscription subscribe();

    /**
     * Asks the runtime to show the user {@code authUrl} (an {@code oauth} message). The call stays
     * pending: the tool completes the authorization through its own OAuth redirect endpoint, then
     * returns its result.
     *
     * @throws IllegalArgumentException if {@code authUrl} is not {@code https}
     */
    void requestAuthorization(String authUrl);

    /** {@link #askUser(String, List, int, Duration)} with the server's default choice timeout. */
    int askUser(String prompt, List<String> choices, int defaultIndex);

    /**
     * Asks the user to choose (a {@code user_choice} message) and waits for the answer.
     *
     * @return the chosen index; {@code defaultIndex} if no answer arrives within {@code timeout}
     * @throws IllegalArgumentException if {@code choices} is empty or {@code defaultIndex} is out of range
     * @throws io.github.rahulbsw.protoweft.core.ProtoweftException if the prompt cannot be delivered,
     *         the answer is out of range, or the call is cancelled
     */
    int askUser(String prompt, List<String> choices, int defaultIndex, Duration timeout);

    /** {@code true} once the runtime asked to cancel this call. */
    boolean isCancelled();

    /** Runs {@code action} when the runtime cancels this call (now, if it already has). */
    void onCancel(Runnable action);
}
