package io.agentweave.core.agent;

import io.agentweave.core.tool.Content;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One message exchanged with an agent.
 *
 * @param role      who sent it
 * @param parts     ordered content parts
 * @param messageId the message identifier, or {@code null} to let the connector assign one
 * @param taskId    the task this message continues, or {@code null} to start new work
 * @param contextId the conversation it belongs to, or {@code null}
 * @param metadata  extra key/values carried with the message
 */
public record AgentMessage(Role role, List<Content> parts, String messageId, String taskId,
                           String contextId, Map<String, Object> metadata) {

    /** Message author. */
    public enum Role { USER, AGENT }

    public AgentMessage {
        Objects.requireNonNull(role, "role");
        parts = parts == null ? List.of() : List.copyOf(parts);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A new user message with one text part. */
    public static AgentMessage user(String text) {
        return new AgentMessage(Role.USER, List.of(new Content.Text(text)), null, null, null, null);
    }

    /** A user message continuing an existing task, for example answering an input request. */
    public static AgentMessage userReply(String taskId, String contextId, String text) {
        return new AgentMessage(Role.USER, List.of(new Content.Text(text)), null, taskId, contextId, null);
    }

    /** An agent message with one text part. */
    public static AgentMessage agent(String text) {
        return new AgentMessage(Role.AGENT, List.of(new Content.Text(text)), null, null, null, null);
    }

    /** Concatenation of the text parts, separated by newlines. */
    public String text() {
        return parts.stream().filter(Content.Text.class::isInstance)
                .map(p -> ((Content.Text) p).text()).collect(Collectors.joining("\n"));
    }
}
