package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.TokenUsage;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.List;

/**
 * One AG-UI run being served: the parent agent's {@link AgUiEmitter} plus the run's lifecycle. End
 * the run exactly once, with {@link #finish}, {@link #interrupt}, {@link #cancel} or {@link #fail}.
 */
public interface AgUiRun extends AgUiEmitter {

    RunAgentInput input();

    /** Who sent the run, as authenticated by the host's {@code InboundAuthenticator}. */
    CallerIdentity caller();

    /**
     * Whether the client went away. AG-UI has no cancel request: a client stops a run by closing the
     * stream, after which nothing more can reach it. Long-running agents should check this.
     */
    boolean isCancelled();

    /** Starts a subagent invocation; its events are attributed to it. */
    Subagent startSubagent(String name, String description);

    /** Token usage reported on the run's terminal event (one entry per provider and model). */
    void usage(List<TokenUsage> usage);

    /**
     * Finishes the run successfully. Client tool calls still unanswered are listed as pending.
     * Throws {@link IllegalStateException} if items are still open.
     */
    default void finish() {
        finish(null);
    }

    /** As {@link #finish()}, with the run's return value (any JSON value). */
    void finish(Object result);

    /** Ends the run waiting for outside input; the next run answers with resume entries. */
    void interrupt(List<Interrupt> interrupts);

    /** Ends a run stopped on purpose before it completed (not a failure). Open items are closed. */
    void cancel();

    /** Ends the run as failed. {@code code} is optional and machine-readable. */
    void fail(String message, String code);

    /** One subagent invocation inside the run. End it with one of its terminal methods. */
    interface Subagent extends AgUiEmitter {

        /** The invocation id ({@code subagentRunId}). */
        String id();

        /** Starts a nested invocation spawned by this one. */
        Subagent startSubagent(String name, String description);

        void finish(Object result);

        /** Pauses the invocation awaiting outside input, naming the interrupts it raised (may be empty). */
        void suspend(List<String> interruptIds);

        void fail(String message, String code);
    }
}
