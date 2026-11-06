package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.List;

/**
 * Anything that offers callable tools: a remote connector, a set of local Java tools, or a
 * {@link ToolRegistry} combining many. Framework adapters (LangChain4j, Spring) and server
 * modules (MCP server, UTCP manual provider) consume this interface.
 */
public interface ToolSource {

    /**
     * Lists every tool on offer.
     *
     * @throws ProtoweftException if the tools cannot be listed
     */
    List<ToolSpec> listTools();

    /**
     * Calls one tool.
     *
     * @return the outcome; a tool-reported failure is an outcome with
     *         {@link ToolOutcome#error()} set, not an exception
     * @throws ProtoweftException if the call could not be carried out
     */
    ToolOutcome invoke(ToolInvocation invocation);

    /**
     * Calls one tool on behalf of an authenticated caller. Server endpoints use this so
     * local tools can see who is calling; sources that don't care inherit the default.
     */
    default ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return invoke(invocation);
    }

    /**
     * Calls one tool on behalf of a caller, forwarding the tool's progress and log messages
     * to {@code progress}. Sources that can't report progress inherit the default, which
     * ignores it.
     */
    default ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
        return invoke(invocation, caller);
    }
}
