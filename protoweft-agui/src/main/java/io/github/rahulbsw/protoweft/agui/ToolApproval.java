package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.Map;

/**
 * Decides whether a remote agent's call to a bridged tool that is not marked {@code readOnly} may
 * run, typically by asking the user. The spec requires consent before side-effectful tool calls
 * and forbids presenting a call as approved when it was not; without an approval step such calls
 * do not run.
 */
@FunctionalInterface
public interface ToolApproval {

    boolean approve(Request call);

    /**
     * One call awaiting a decision.
     *
     * @param arguments the parsed, structurally checked arguments (model output: show, don't trust)
     */
    record Request(String toolCallId, ToolSpec tool, Map<String, Object> arguments) {
    }
}
