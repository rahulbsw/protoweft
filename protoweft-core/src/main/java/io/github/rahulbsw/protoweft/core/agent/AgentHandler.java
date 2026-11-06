package io.github.rahulbsw.protoweft.core.agent;

/**
 * The implementation of an agent served by an Protoweft agent server (for example the
 * A2A server). One handler serves every task; each call runs on its own thread.
 *
 * <pre>{@code
 * AgentHandler echo = (request, task) -> {
 *     if (request.text().isBlank()) {
 *         task.requireInput("What should I echo?");
 *         return;
 *     }
 *     task.working("echoing");
 *     task.artifact("echo", List.of(new Content.Text(request.text())));
 *     task.complete();
 * };
 * }</pre>
 */
@FunctionalInterface
public interface AgentHandler {

    void handle(AgentRequest request, AgentTaskContext task) throws Exception;

    /**
     * Called when the caller cancels a running task. The default does nothing; the server
     * marks the task canceled and {@link AgentTaskContext#isCancelled()} returns
     * {@code true}.
     */
    default void onCancel(String taskId) {
    }
}
