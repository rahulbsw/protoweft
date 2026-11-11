package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.agui.AgUiClientConnector;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code protoweft.agui.agents.*} → {@link AgUiClientConnector}s. */
final class AgUiConnectorFactory implements ConnectorFactory {

    @Override
    public Map<String, ToolConnector> create(ProtoweftProperties properties, CredentialProvider credentials,
                                             ObservationHandler observations) {
        Map<String, ToolConnector> out = new LinkedHashMap<>();
        properties.getAgui().getAgents().forEach((name, a) -> out.put(name, AgUiClientConnector.builder(a.getUrl())
                .client(b -> {
                    b.credentials(credentials);
                    if (a.getResponseTimeout() != null) {
                        b.responseTimeout(a.getResponseTimeout());
                    }
                })
                .build()));
        return out;
    }
}
