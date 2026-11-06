package io.github.rahulbsw.protoweft.core.agent;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.List;
import java.util.Objects;

/**
 * An incoming message for an {@link AgentHandler}.
 *
 * @param message   the message just received
 * @param taskId    the task it belongs to (new or existing)
 * @param contextId the conversation it belongs to
 * @param resumed   {@code true} if the message continues an existing task, for example
 *                  answering an earlier {@link AgentTaskContext#requireInput(String)}
 * @param history   earlier messages of the task, oldest first (empty for new work)
 * @param caller    the authenticated caller
 */
public record AgentRequest(AgentMessage message, String taskId, String contextId, boolean resumed,
                           List<AgentMessage> history, CallerIdentity caller) {

    public AgentRequest {
        Objects.requireNonNull(message, "message");
        history = history == null ? List.of() : List.copyOf(history);
        caller = caller == null ? CallerIdentity.ANONYMOUS : caller;
    }

    /** Text of the message just received. */
    public String text() {
        return message.text();
    }
}
