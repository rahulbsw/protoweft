package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.utcp.UtcpClientConnector;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code protoweft.utcp.manuals.*} → {@link UtcpClientConnector}s. */
final class UtcpConnectorFactory implements ConnectorFactory {

    @Override
    public Map<String, ToolConnector> create(ProtoweftProperties properties, CredentialProvider credentials,
                                             ObservationHandler observations) {
        Map<String, ToolConnector> out = new LinkedHashMap<>();
        properties.getUtcp().getManuals().forEach((name, m) -> {
            UtcpClientConnector.Builder b = m.getUrl() != null && !m.getUrl().isBlank()
                    ? UtcpClientConnector.http(name, m.getUrl())
                    : UtcpClientConnector.file(name, m.getFile());
            m.getHeaders().forEach(b::header);
            // UTCP looks variables up namespaced by the sanitized manual name (see UtcpClientConnector).
            String namespace = name.replaceAll("[^\\w]", "_").replace("_", "__");
            m.getVariables().forEach((k, v) -> b.variable(namespace + "_" + k, v));
            if (m.getDiscoveryTimeout() != null) {
                b.discoveryTimeout(m.getDiscoveryTimeout());
            }
            if (m.getCallTimeout() != null) {
                b.callTimeout(m.getCallTimeout());
            }
            out.put(name, b.credentials(credentials).observations(observations).build());
        });
        return out;
    }
}
