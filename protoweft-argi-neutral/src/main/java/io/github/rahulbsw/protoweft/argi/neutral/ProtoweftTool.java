package io.github.rahulbsw.protoweft.argi.neutral;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * One Protoweft tool as an ARGI {@link Tool}: bound to its original name, it runs
 * {@link ToolSource#invoke(ToolInvocation, CallerIdentity, ToolProgress)} on the configured executor.
 */
final class ProtoweftTool implements Tool {

    private final ToolSource source;
    private final String toolName;
    private final ToolDefinition definition;
    private final Function<ToolContext, CallerIdentity> caller;
    private final Executor executor;

    ProtoweftTool(ToolSource source, String toolName, ToolDefinition definition,
                   Function<ToolContext, CallerIdentity> caller, Executor executor) {
        this.source = Objects.requireNonNull(source, "source");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** The Protoweft name this tool calls. */
    String toolName() {
        return toolName;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
        ToolContext ctx = context == null ? ToolContext.empty() : context;
        Map<String, Object> arguments;
        try {
            arguments = Json.parseObject(call.arguments());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Results.failure(new ProtoweftException(
                    ErrorCategory.INVALID_ARGUMENTS, null,
                    "arguments for tool '" + definition.name() + "' are not a JSON object: " + e.getMessage(), e)));
        }
        CallerIdentity who = caller.apply(ctx);
        CallerIdentity identity = who == null ? CallerIdentity.ANONYMOUS : who;
        ToolProgress progress = progress(ctx);
        return CompletableFuture.supplyAsync(() -> run(arguments, identity, progress), executor);
    }

    private ToolResult run(Map<String, Object> arguments, CallerIdentity identity, ToolProgress progress) {
        ToolOutcome outcome;
        try {
            outcome = source.invoke(new ToolInvocation(toolName, arguments), identity, progress);
        } catch (ProtoweftException e) {
            return Results.failure(e);
        }
        return Results.toResult(outcome == null ? new ToolOutcome(null, false, null) : outcome);
    }

    private static ToolProgress progress(ToolContext context) {
        Object value = context.attributes().get(ArgiNeutralTools.PROGRESS_KEY);
        return value instanceof ToolProgress p ? p : ToolProgress.NOOP;
    }

    @Override
    public String toString() {
        return "ProtoweftTool[" + definition.name() + " -> " + toolName + "]";
    }
}
