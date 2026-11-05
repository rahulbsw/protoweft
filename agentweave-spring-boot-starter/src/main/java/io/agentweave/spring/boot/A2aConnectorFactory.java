package io.agentweave.spring.boot;

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.tool.ToolConnector;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code agentweave.a2a.agents.*} → {@link A2aAgentConnector}s. */
final class A2aConnectorFactory implements ConnectorFactory {

    @Override
    public Map<String, ToolConnector> create(AgentWeaveProperties properties, CredentialProvider credentials,
                                             ObservationHandler observations) {
        Map<String, ToolConnector> out = new LinkedHashMap<>();
        properties.getA2a().getAgents().forEach((name, a) -> {
            A2aAgentConnector.Builder b = A2aAgentConnector.builder(a.getUrl())
                    .credentials(credentials).observations(observations).streaming(a.isStreaming());
            if (a.getTimeout() != null) {
                b.timeout(a.getTimeout());
            }
            out.put(name, b.build());
        });
        return out;
    }
}
