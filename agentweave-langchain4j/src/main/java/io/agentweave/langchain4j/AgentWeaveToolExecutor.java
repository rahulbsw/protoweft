package io.agentweave.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Runs one AgentWeave tool for LangChain4j; error handling is described in SPEC.md. */
final class AgentWeaveToolExecutor implements ToolExecutor {

    private final ToolSource source;
    private final String toolName;
    private final Function<InvocationContext, CallerIdentity> caller;
    private final Executor asyncExecutor;

    AgentWeaveToolExecutor(ToolSource source, String toolName,
                           Function<InvocationContext, CallerIdentity> caller, Executor asyncExecutor) {
        this.source = source;
        this.toolName = toolName;
        this.caller = caller;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request, InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        Map<String, Object> arguments;
        try {
            arguments = Json.parseObject(request.arguments());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            String message = "arguments for tool '" + request.name() + "' are not a JSON object: "
                    + (e instanceof JsonProcessingException jpe ? jpe.getOriginalMessage() : e.getMessage());
            throw new ToolArgumentsException(message,
                    new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, null, message, e));
        }
        CallerIdentity who = caller.apply(context);
        ToolOutcome outcome;
        try {
            outcome = source.invoke(new ToolInvocation(toolName, arguments), who == null ? CallerIdentity.ANONYMOUS : who);
        } catch (AgentWeaveException e) {
            // Wrapped so LangChain4j's error handlers receive the AgentWeaveException itself.
            throw e.category() == ErrorCategory.INVALID_ARGUMENTS
                    ? new ToolArgumentsException(e.getMessage(), e)
                    : new ToolExecutionException(e.getMessage(), e);
        }
        return toResult(outcome);
    }

    @Override
    public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request, InvocationContext context) {
        return CompletableFuture.supplyAsync(() -> executeWithContext(request, context), asyncExecutor);
    }

    private ToolExecutionResult toResult(ToolOutcome outcome) {
        String text = textFor(outcome);
        if (outcome.error() && text.isBlank()) {
            text = "tool '" + toolName + "' failed without details";
        }
        return ToolExecutionResult.builder()
                .isError(outcome.error())
                .result(outcome)
                .resultText(text)
                .build();
    }

    /** Text the LLM sees: text and JSON parts verbatim, links as URIs, other parts as visible markers. */
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
}
