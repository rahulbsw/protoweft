package io.github.rahulbsw.protoweft.core.agent;

import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.List;

/**
 * How an {@link AgentHandler} reports progress and results. Calls are forwarded to the
 * caller as they happen (streamed when the client streams).
 *
 * <p>Either answer with a single {@link #reply} message, or work on a task: report
 * {@link #working}, stream the answer with {@link #textDelta}, add {@link #artifact}s, and
 * finish with {@link #complete}, {@link #fail}, {@link #reject}, or pause with
 * {@link #requireInput} / {@link #requireAuth}. If the handler returns without finishing or pausing, the task is
 * completed automatically; if it throws, the task fails with the exception message.
 */
public interface AgentTaskContext {

    String taskId();

    String contextId();

    /** Answers with a direct message and no task. Must be the only call made. */
    void reply(AgentMessage message);

    default void reply(String text) {
        reply(AgentMessage.agent(text));
    }

    /** Marks the task as in progress, with an optional status message ({@code null} for none). */
    void working(String status);

    /**
     * Streams the next fragment of the agent's answer text, as a model produces it. The fragments,
     * in order, are the answer: a handler that streams does not also send the whole text as an
     * artifact. Each protocol carries them in its own streaming form (A2A: chunks of one appended
     * artifact; AG-UI: text message content). Empty fragments are ignored.
     *
     * <p>The default reports the fragment as {@link #working} status, for implementations that
     * have no streaming form.
     */
    default void textDelta(String delta) {
        working(delta);
    }

    /**
     * Reports that the agent itself is calling a tool, so the caller can show it. {@code callId}
     * is unique within the task and ties the call to its {@link #toolResult}; {@code arguments} is
     * the complete argument text, conventionally JSON. Purely informational: nothing is executed.
     * The default ignores it, for protocols with no way to show a tool call (A2A).
     */
    default void toolCall(String callId, String toolName, String arguments) {
    }

    /** Reports the result of a call announced with {@link #toolCall}. The default ignores it. */
    default void toolResult(String callId, ToolOutcome outcome) {
    }

    /** Adds a result to the task. */
    void artifact(AgentArtifact artifact);

    default void artifact(String name, List<Content> parts) {
        artifact(AgentArtifact.of(name, parts));
    }

    /** Pauses the task until the caller sends another message on it. */
    void requireInput(String question);

    /** Pauses the task until the caller authenticates. */
    void requireAuth(String reason);

    /** Completes the task, with an optional final message ({@code null} for none). */
    void complete(String message);

    default void complete() {
        complete(null);
    }

    /** Fails the task with a reason. */
    void fail(String reason);

    /** Declines the work. */
    void reject(String reason);

    /** Whether the caller has asked to cancel this task; long-running handlers should check it. */
    boolean isCancelled();
}
