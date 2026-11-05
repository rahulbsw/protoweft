package io.agentweave.spring.boot;

import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;

import java.util.List;

/**
 * A connector declared in properties, as a capability-catalog source. It lists tools only while the
 * connector is {@link AgentWeaveConnectors.State#UP}, so the catalog never talks to a connector that
 * hasn't started (or has failed or closed) and reports it unavailable instead. It stays a
 * {@link ToolConnector} so the catalog reads the connector's protocol and compliance report. Its
 * lifecycle belongs to {@link AgentWeaveConnectors}: {@link #start()} and {@link #close()} do nothing.
 */
final class DeclaredConnectorSource implements ToolConnector {

    private final String name;
    private final ToolConnector connector;
    private final AgentWeaveConnectors connectors;

    DeclaredConnectorSource(String name, ToolConnector connector, AgentWeaveConnectors connectors) {
        this.name = name;
        this.connector = connector;
        this.connectors = connectors;
    }

    /** Whether the connector has started and is registered. */
    boolean up() {
        return state() == AgentWeaveConnectors.State.UP;
    }

    private AgentWeaveConnectors.State state() {
        AgentWeaveConnectors.Status status = connectors.status().get(name);
        return status == null ? AgentWeaveConnectors.State.CLOSED : status.state();
    }

    @Override
    public List<ToolSpec> listTools() {
        AgentWeaveConnectors.State state = state();
        if (state != AgentWeaveConnectors.State.UP) {
            throw new IllegalStateException("connector '" + name + "' is " + state);
        }
        return connector.listTools();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return connector.invoke(invocation);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return connector.invoke(invocation, caller);
    }

    @Override
    public ProtocolId protocol() {
        return connector.protocol();
    }

    @Override
    public ComplianceReport compliance() {
        return connector.compliance();
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }
}
