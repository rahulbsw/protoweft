package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.List;

/**
 * A connector declared in properties, as a capability-catalog source. It lists tools only while the
 * connector is {@link ProtoweftConnectors.State#UP}, so the catalog never talks to a connector that
 * hasn't started (or has failed or closed) and reports it unavailable instead. It stays a
 * {@link ToolConnector} so the catalog reads the connector's protocol and compliance report. Its
 * lifecycle belongs to {@link ProtoweftConnectors}: {@link #start()} and {@link #close()} do nothing.
 */
final class DeclaredConnectorSource implements ToolConnector {

    private final String name;
    private final ToolConnector connector;
    private final ProtoweftConnectors connectors;

    DeclaredConnectorSource(String name, ToolConnector connector, ProtoweftConnectors connectors) {
        this.name = name;
        this.connector = connector;
        this.connectors = connectors;
    }

    /** Whether the connector has started and is registered. */
    boolean up() {
        return state() == ProtoweftConnectors.State.UP;
    }

    private ProtoweftConnectors.State state() {
        ProtoweftConnectors.Status status = connectors.status().get(name);
        return status == null ? ProtoweftConnectors.State.CLOSED : status.state();
    }

    @Override
    public List<ToolSpec> listTools() {
        ProtoweftConnectors.State state = state();
        if (state != ProtoweftConnectors.State.UP) {
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
