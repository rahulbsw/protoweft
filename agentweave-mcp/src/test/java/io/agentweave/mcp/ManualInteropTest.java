package io.agentweave.mcp;

import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in interop against the TypeScript reference server, for example:
 * {@code PORT=3001 npx -y @modelcontextprotocol/server-everything streamableHttp}, then run with
 * {@code AGENTWEAVE_MCP_INTEROP_URL=http://localhost:3001}.
 */
@EnabledIfEnvironmentVariable(named = "AGENTWEAVE_MCP_INTEROP_URL", matches = ".+")
class ManualInteropTest {

    @Test
    void streamableHttpClientTalksToTheReferenceServer() {
        try (McpClientConnector mcp = McpClientConnector.http(System.getenv("AGENTWEAVE_MCP_INTEROP_URL")).build()) {
            mcp.start();
            System.err.println("negotiated " + mcp.compliance().negotiated().orElseThrow());
            System.err.println("tools " + mcp.listTools().stream().map(ToolSpec::name).toList());

            assertThat(mcp.invoke(ToolInvocation.of("echo", Map.of("message", "hi"))).text()).contains("hi");
            System.err.println("get-tiny-image " + mcp.invoke(ToolInvocation.of("get-tiny-image", Map.of())).content()
                    .stream().map(c -> c.getClass().getSimpleName()).toList());
            System.err.println("get-resource-links " + mcp.invoke(ToolInvocation.of("get-resource-links", Map.of()))
                    .content().stream().map(c -> c.getClass().getSimpleName()).toList());
        }
    }
}
