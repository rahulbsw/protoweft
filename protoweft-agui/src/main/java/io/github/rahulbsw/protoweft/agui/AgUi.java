package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Bridges Protoweft's protocol-neutral agent contract and AG-UI.
 *
 * <p>{@link #agent(AgentHandler)} serves any {@link AgentHandler} over AG-UI:
 * <ul>
 *   <li>the request's message is the last user message of the run input and its history the
 *       user and assistant messages before it; {@code taskId} and {@code contextId} are the
 *       {@code threadId}, stable across runs;</li>
 *   <li>a run carrying resume entries is a resumed request whose message holds the answers: string
 *       payloads as text, others as JSON parts, all entries under metadata key
 *       {@value HandlerAdapter#RESUME_METADATA};</li>
 *   <li>{@code textDelta} streams a text message; {@code toolCall}/{@code toolResult} become tool
 *       call events; {@code reply}, {@code complete(message)} and text artifacts become text
 *       messages; non-text artifacts and {@code working} status become activity messages
 *       ({@code artifact}, {@code status});</li>
 *   <li>{@code requireInput}/{@code requireAuth} end the run with an interrupt (reasons
 *       {@code input_required}, {@code auth_required}); {@code fail} ends it with
 *       {@code RUN_ERROR}, {@code reject} with code {@code rejected};</li>
 *   <li>a client disconnect sets {@code isCancelled()} and calls {@code onCancel(threadId)}.</li>
 * </ul>
 *
 * <p>{@link #run(AgentTaskContext)} gives such a handler the AG-UI features the neutral contract
 * has no words for (shared state, activity, reasoning, subagents, the application's own tools).
 */
public final class AgUi {

    private AgUi() {
    }

    /** An AG-UI agent that runs {@code handler}. */
    public static AgUiAgent agent(AgentHandler handler) {
        return new HandlerAdapter(Objects.requireNonNull(handler, "handler"));
    }

    /** The AG-UI run behind {@code task}, when the task is being served over AG-UI. */
    public static Optional<AgUiRun> run(AgentTaskContext task) {
        return task instanceof HandlerAdapter.TaskContext t ? Optional.of(t.run()) : Optional.empty();
    }
}
