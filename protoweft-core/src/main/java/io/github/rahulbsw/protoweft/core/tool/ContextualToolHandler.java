package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.Map;

/**
 * A {@link ProgressAwareToolHandler} that also receives the call's {@link ToolContexts}, for tools
 * that use a protocol's {@link ToolContext} (for example RAP's {@code RapCallContext}). Exceptions
 * behave as for {@link ToolHandler}.
 */
@FunctionalInterface
public interface ContextualToolHandler {

    ToolOutcome handle(Map<String, Object> arguments, CallerIdentity caller, ToolProgress progress,
                       ToolContexts contexts) throws Exception;
}
