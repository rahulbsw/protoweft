package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.Map;

/**
 * The implementation of a programmatically registered tool.
 *
 * <p>Throwing an exception reports a tool failure: it becomes a {@link ToolOutcome} with
 * {@code error} set, except an {@link io.github.rahulbsw.protoweft.core.ProtoweftException}, which
 * propagates unchanged.
 */
@FunctionalInterface
public interface ToolHandler {

    ToolOutcome handle(Map<String, Object> arguments, CallerIdentity caller) throws Exception;
}
