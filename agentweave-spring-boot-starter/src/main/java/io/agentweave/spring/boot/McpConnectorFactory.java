package io.agentweave.spring.boot;

import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.mcp.McpClientConnector;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code agentweave.mcp.clients.*} → {@link McpClientConnector}s. */
final class McpConnectorFactory implements ConnectorFactory {

    @Override
    public Map<String, ToolConnector> create(AgentWeaveProperties properties, CredentialProvider credentials,
                                             ObservationHandler observations) {
        Map<String, ToolConnector> out = new LinkedHashMap<>();
        properties.getMcp().getClients().forEach((name, c) -> {
            if (c.getCommand() != null && !c.getCommand().isBlank()) {
                McpClientConnector.Builder b = McpClientConnector.stdio(c.getCommand(), c.getArgs().toArray(String[]::new))
                        .credentials(credentials).observations(observations);
                c.getEnv().forEach(b::env);
                if (c.getRequestTimeout() != null) {
                    b.requestTimeout(c.getRequestTimeout());
                }
                out.put(name, b.build());
            } else {
                McpClientConnector.HttpBuilder b = McpClientConnector.http(c.getUrl()).endpoint(c.getEndpoint())
                        .credentials(credentials).observations(observations);
                c.getHeaders().forEach(b::header);
                if (c.getRequestTimeout() != null) {
                    b.requestTimeout(c.getRequestTimeout());
                }
                out.put(name, b.build());
            }
        });
        return out;
    }
}
