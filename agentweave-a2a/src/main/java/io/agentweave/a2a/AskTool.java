package io.agentweave.a2a;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.agent.AgentArtifact;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentReply;
import io.agentweave.core.agent.TaskState;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single {@code ask} tool an {@link A2aAgentConnector} exposes as a {@code ToolConnector} (see SPEC.md). */
final class AskTool {

    static final String NAME = "ask";

    private AskTool() {
    }

    static ToolSpec spec(AgentDescriptor agent) {
        StringBuilder description = new StringBuilder();
        description.append("Send a message to the remote A2A agent '").append(agent.name()).append("'");
        if (!agent.description().isBlank()) {
            description.append(": ").append(agent.description());
        }
        if (!agent.skills().isEmpty()) {
            description.append("\nSkills:");
            for (AgentDescriptor.Skill skill : agent.skills()) {
                description.append("\n- ").append(skill.name());
                if (!skill.description().isBlank()) {
                    description.append(": ").append(skill.description());
                }
            }
        }
        description.append("\nIf the reply says the agent needs input, answer with the returned task_id and context_id.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "The message to send to the agent"));
        properties.put("task_id", Map.of("type", "string",
                "description", "Continue this task, for example to answer the agent's question"));
        properties.put("context_id", Map.of("type", "string", "description", "The conversation to continue"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("message"));
        return new ToolSpec(NAME, description.toString(), schema);
    }

    static AgentMessage message(ToolInvocation invocation) {
        if (!NAME.equals(invocation.toolName())) {
            throw new AgentWeaveException(ErrorCategory.TOOL_NOT_FOUND, ProtocolId.A2A,
                    "unknown tool '" + invocation.toolName() + "'; an A2A agent offers only '" + NAME + "'");
        }
        Object text = invocation.arguments().get("message");
        if (!(text instanceof String s) || s.isBlank()) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.A2A,
                    "'message' is required and must be a non-empty string");
        }
        return new AgentMessage(AgentMessage.Role.USER, List.of(new Content.Text(s)), null,
                optionalString(invocation, "task_id"), optionalString(invocation, "context_id"), null);
    }

    static ToolOutcome outcome(AgentReply reply) {
        List<Content> content = new ArrayList<>();
        String text = reply.text();
        if (text.isBlank() && reply.state().isInterrupted()) {
            text = "The agent is waiting (" + A2aMapping.stateName(reply.state()) + ") on task " + reply.taskId()
                    + "; reply with task_id and context_id to continue.";
        } else if (text.isBlank() && reply.state() != TaskState.COMPLETED) {
            text = "The agent task ended in state " + A2aMapping.stateName(reply.state()) + ".";
        }
        if (!text.isEmpty()) {
            content.add(new Content.Text(text));
        }
        // Non-text parts are kept, never silently dropped.
        if (reply.message() != null) {
            addNonText(reply.message().parts(), content);
        }
        for (AgentArtifact artifact : reply.artifacts()) {
            addNonText(artifact.parts(), content);
        }
        boolean error = reply.state() == TaskState.FAILED || reply.state() == TaskState.REJECTED
                || reply.state() == TaskState.CANCELED;
        Map<String, Object> structured = null;
        if (reply.task()) {
            structured = new LinkedHashMap<>();
            structured.put("task_id", reply.taskId());
            structured.put("context_id", reply.contextId());
            structured.put("state", A2aMapping.stateName(reply.state()));
        }
        return new ToolOutcome(content, error, structured);
    }

    private static void addNonText(List<Content> parts, List<Content> into) {
        for (Content part : parts) {
            if (!(part instanceof Content.Text)) {
                into.add(part);
            }
        }
    }

    private static String optionalString(ToolInvocation invocation, String name) {
        Object value = invocation.arguments().get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.A2A, "'" + name + "' must be a string");
        }
        return s.isBlank() ? null : s;
    }
}
