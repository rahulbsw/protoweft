package io.agentweave.core.agent;

import io.agentweave.core.tool.Content;

import java.util.List;

/**
 * How an {@link AgentHandler} reports progress and results. Calls are forwarded to the
 * caller as they happen (streamed when the client streams).
 *
 * <p>Either answer with a single {@link #reply} message, or work on a task: report
 * {@link #working}, add {@link #artifact}s, and finish with {@link #complete},
 * {@link #fail}, {@link #reject}, or pause with {@link #requireInput} /
 * {@link #requireAuth}. If the handler returns without finishing or pausing, the task is
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
