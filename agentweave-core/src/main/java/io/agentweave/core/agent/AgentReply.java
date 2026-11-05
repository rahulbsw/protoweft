package io.agentweave.core.agent;

import io.agentweave.core.tool.Content;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What an agent returned: either a direct message, or a task with a state and artifacts.
 *
 * @param task      {@code true} if the agent created or updated a task
 * @param taskId    the task id, or {@code null} for a direct message
 * @param contextId the conversation id, or {@code null}
 * @param state     the task state; {@link TaskState#COMPLETED} for a direct message
 * @param message   the reply message, or the task's latest status message; may be {@code null}
 * @param artifacts artifacts produced so far
 */
public record AgentReply(boolean task, String taskId, String contextId, TaskState state,
                         AgentMessage message, List<AgentArtifact> artifacts) {

    public AgentReply {
        Objects.requireNonNull(state, "state");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static AgentReply ofMessage(AgentMessage message) {
        return new AgentReply(false, message.taskId(), message.contextId(), TaskState.COMPLETED, message, List.of());
    }

    /** The message text followed by any text in the artifacts. */
    public String text() {
        List<String> texts = new ArrayList<>();
        if (message != null && !message.text().isEmpty()) {
            texts.add(message.text());
        }
        for (AgentArtifact artifact : artifacts) {
            String t = artifact.parts().stream().filter(Content.Text.class::isInstance)
                    .map(p -> ((Content.Text) p).text()).collect(Collectors.joining("\n"));
            if (!t.isEmpty()) {
                texts.add(t);
            }
        }
        return String.join("\n", texts);
    }
}
