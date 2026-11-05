package io.agentweave.springai;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolProgress;
import io.agentweave.core.tool.ToolSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Runs one AgentWeave tool for Spring AI; error handling is described in SPEC.md. */
final class AgentWeaveToolCallback implements ToolCallback {

    private final ToolSource source;
    private final String toolName;
    private final ToolDefinition definition;
    private final Function<ToolContext, CallerIdentity> caller;

    AgentWeaveToolCallback(ToolSource source, String toolName, ToolDefinition definition,
                           Function<ToolContext, CallerIdentity> caller) {
        this.source = source;
        this.toolName = toolName;
        this.definition = definition;
        this.caller = caller;
    }

    /** The AgentWeave name of the tool, as the source knows it. */
    String toolName() {
        return toolName;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> arguments;
        try {
            arguments = Json.parseObject(toolInput);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(definition, new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, null,
                    "arguments for tool '" + definition.name() + "' are not a JSON object: " + e.getMessage(), e));
        }
        CallerIdentity who = caller.apply(toolContext);
        ToolOutcome outcome;
        try {
            outcome = source.invoke(new ToolInvocation(toolName, arguments),
                    who == null ? CallerIdentity.ANONYMOUS : who, progress(toolContext));
        } catch (AgentWeaveException e) {
            throw new ToolExecutionException(definition, e);
        }
        String text = textFor(outcome);
        if (outcome.error()) {
            throw new ToolExecutionException(definition, new ToolOutcomeException(
                    text.isBlank() ? "tool '" + definition.name() + "' failed without details" : text, outcome));
        }
        return text;
    }

    private static ToolProgress progress(ToolContext context) {
        Object value = context == null ? null : context.getContext().get(AgentWeaveToolCallbacks.PROGRESS_KEY);
        return value instanceof ToolProgress p ? p : ToolProgress.NOOP;
    }

    /** Text the model sees: text and JSON parts verbatim, links as URIs, other parts as visible markers. */
    static String textFor(ToolOutcome outcome) {
        List<String> lines = new ArrayList<>();
        for (Content part : outcome.content()) {
            if (part instanceof Content.Text t) {
                lines.add(t.text());
            } else if (part instanceof Content.Json j) {
                lines.add(Json.write(j.value()));
            } else if (part instanceof Content.Link l) {
                lines.add(l.uri());
            } else if (part instanceof Content.Data d) {
                lines.add("[" + d.mimeType() + " data not forwarded]");
            } else if (part instanceof Content.Unmapped u) {
                lines.add("[unmapped " + u.protocolType() + " content]");
            }
        }
        if (lines.isEmpty() && outcome.structured() != null) {
            lines.add(Json.write(outcome.structured()));
        }
        return String.join("\n", lines);
    }

    @Override
    public String toString() {
        return "AgentWeaveToolCallback[" + definition.name() + " -> " + toolName + "]";
    }
}
