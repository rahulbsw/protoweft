package io.agentweave.spring.boot;

import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.tool.ToolConnector;

import java.util.Map;

/** Builds (not starts) the connectors one protocol module declares in {@link AgentWeaveProperties}. */
interface ConnectorFactory {

    /** Connectors by registry name, in declaration order. */
    Map<String, ToolConnector> create(AgentWeaveProperties properties, CredentialProvider credentials,
                                      ObservationHandler observations);
}
