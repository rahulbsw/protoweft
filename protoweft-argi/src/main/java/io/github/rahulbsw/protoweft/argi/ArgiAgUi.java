package io.github.rahulbsw.protoweft.argi;

import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.rahulbsw.protoweft.agui.AgUi;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiRun;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool approvals as AG-UI interrupts. The only class of this module that touches
 * {@code protoweft-agui}, which is optional: {@link ArgiAgentHandler} loads it only after checking
 * that module is present.
 */
final class ArgiAgUi {

    /** Interrupt reason for a tool call awaiting approval. */
    static final String REASON = "tool_approval";

    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "decision", Map.of("type", "string", "enum", List.of("approved", "rejected", "edited")),
                    "arguments", Map.of("type", "object", "description", "Edited arguments (decision \"edited\")"),
                    "reason", Map.of("type", "string", "description", "Why the call was rejected")),
            "required", List.of("decision"));

    private ArgiAgUi() {
    }

    /**
     * Ends the AG-UI run behind {@code task} with one interrupt per undecided call (id = the ARGI
     * tool call id); {@code false} if the task is not an AG-UI run.
     */
    static boolean interrupt(AgentTaskContext task, List<ToolFeedback> calls, Map<String, ToolFeedback> decided,
                             String problem) {
        Optional<AgUiRun> run = AgUi.run(task);
        if (run.isEmpty()) {
            return false;
        }
        List<Interrupt> interrupts = calls.stream().filter(c -> !decided.containsKey(c.getId())).map(c -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("toolName", c.getName());
            metadata.put("arguments", c.getArguments());
            String message = (problem == null ? "" : problem + "\n") + "Approve the call to " + c.getName()
                    + (c.getDescription() == null || c.getDescription().isBlank() ? "" : " (" + c.getDescription().strip() + ")")
                    + " with arguments " + c.getArguments() + "?";
            return new Interrupt(c.getId(), REASON, message, null, RESPONSE_SCHEMA, null, metadata, null);
        }).toList();
        run.get().interrupt(interrupts);
        return true;
    }
}
