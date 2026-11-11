package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The {@code ask} tool through which a registry or a model talks to a remote AG-UI agent. */
final class AskTool {

    static final String NAME = "ask";

    private AskTool() {
    }

    static ToolSpec spec(AgentDescriptor agent) {
        String description = "Send a message to the remote agent '" + agent.name() + "'"
                + (agent.description().isBlank() ? "" : ": " + agent.description())
                + "\nIf the reply says the agent needs input, answer with the returned conversation_id.";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "The message to send to the agent"));
        properties.put("conversation_id", Map.of("type", "string",
                "description", "Continue this conversation, for example to answer the agent's question"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("message"));
        return new ToolSpec(NAME, description, schema);
    }

    static AgentMessage message(ToolInvocation invocation) {
        if (!NAME.equals(invocation.toolName())) {
            throw new ProtoweftException(ErrorCategory.TOOL_NOT_FOUND, ProtocolId.AGUI,
                    "unknown tool '" + invocation.toolName() + "'; an AG-UI agent offers only '" + NAME + "'");
        }
        if (!(invocation.arguments().get("message") instanceof String text) || text.isBlank()) {
            throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.AGUI,
                    "'message' is required and must be a non-empty string");
        }
        String conversation = invocation.arguments().get("conversation_id") instanceof String s && !s.isBlank()
                ? s : null;
        return new AgentMessage(AgentMessage.Role.USER, List.of(new Content.Text(text)), null, conversation,
                conversation, null);
    }

    static ToolOutcome outcome(AgentReply reply) {
        String text = reply.text();
        if (text.isBlank()) {
            text = reply.state() == TaskState.COMPLETED ? "" : "The agent's turn ended in state " + reply.state() + ".";
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("conversation_id", reply.contextId());
        structured.put("state", reply.state().name().toLowerCase(Locale.ROOT));
        boolean error = reply.state() == TaskState.FAILED || reply.state() == TaskState.CANCELED
                || reply.state() == TaskState.UNKNOWN;
        return new ToolOutcome(text.isEmpty() ? List.of() : List.of(new Content.Text(text)), error, structured);
    }
}
