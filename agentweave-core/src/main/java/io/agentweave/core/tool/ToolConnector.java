package io.agentweave.core.tool;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ProtocolConnector;

import java.util.List;

/**
 * A connector that can discover and call tools on a peer. Application code should depend
 * on this interface, not on a protocol-specific implementation.
 */
public interface ToolConnector extends ProtocolConnector {

    /**
     * Lists every tool the peer offers, following pagination where the protocol has it.
     *
     * @throws AgentWeaveException if the peer cannot be queried
     */
    List<ToolSpec> listTools();

    /**
     * Calls one tool.
     *
     * @return the outcome; a tool-reported failure is an outcome with
     *         {@link ToolOutcome#error()} set, not an exception
     * @throws AgentWeaveException if the call could not be carried out
     */
    ToolOutcome invoke(ToolInvocation invocation);
}
