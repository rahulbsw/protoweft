package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.Map;

/**
 * A {@link ToolHandler} that also receives a {@link ToolProgress} for progress and log
 * notifications. Exceptions behave as for {@link ToolHandler}.
 */
@FunctionalInterface
public interface ProgressAwareToolHandler {

    ToolOutcome handle(Map<String, Object> arguments, CallerIdentity caller, ToolProgress progress) throws Exception;
}
