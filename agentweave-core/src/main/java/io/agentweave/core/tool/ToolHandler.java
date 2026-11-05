package io.agentweave.core.tool;

import io.agentweave.core.server.CallerIdentity;

import java.util.Map;

/**
 * The implementation of a programmatically registered tool.
 *
 * <p>Throwing an exception reports a tool failure: it becomes a {@link ToolOutcome} with
 * {@code error} set, except an {@link io.agentweave.core.AgentWeaveException}, which
 * propagates unchanged.
 */
@FunctionalInterface
public interface ToolHandler {

    ToolOutcome handle(Map<String, Object> arguments, CallerIdentity caller) throws Exception;
}
