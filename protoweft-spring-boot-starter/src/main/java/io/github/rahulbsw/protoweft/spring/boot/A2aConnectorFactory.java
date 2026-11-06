package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code protoweft.a2a.agents.*} → {@link A2aAgentConnector}s. */
final class A2aConnectorFactory implements ConnectorFactory {

    @Override
    public Map<String, ToolConnector> create(ProtoweftProperties properties, CredentialProvider credentials,
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
