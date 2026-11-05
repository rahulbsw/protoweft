package io.agentweave.mcp;

import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.server.jetty.AgentWeaveServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavior hints declared on a local tool survive MCP serving and MCP client listing. */
class McpToolMetadataTest {

    public static class Files {
        @AgentTool(description = "Reads a file", readOnly = true, tags = {"files"})
        public String read(String path) {
            return "contents";
        }

        @AgentTool(description = "Deletes a file", destructive = true)
        public void delete(String path) {
        }

        @AgentTool(description = "No hints")
        public String plain() {
            return "x";
        }
    }

    @Test
    void readOnlyAndDestructiveHintsRoundTripAsMcpAnnotations() {
        LocalTools tools = LocalTools.builder().scan(new Files()).build();
        try (AgentWeaveServer server = AgentWeaveServer.builder().port(0).endpoints(McpToolServer.http(tools)).start();
             McpClientConnector client = McpClientConnector.http(server.baseUrl()).build()) {
            client.start();
            Map<String, ToolSpec> byName = new java.util.HashMap<>();
            for (ToolSpec spec : client.listTools()) {
                byName.put(spec.name(), spec);
            }

            assertThat(byName.get("read").metadata()).containsEntry(ToolSpec.META_READ_ONLY, true)
                    .doesNotContainKey(ToolSpec.META_DESTRUCTIVE);
            assertThat(byName.get("delete").metadata()).containsEntry(ToolSpec.META_DESTRUCTIVE, true);
            assertThat(byName.get("plain").metadata()).isEmpty();
        }
        ToolSpec read = tools.listTools().stream().filter(t -> t.name().equals("read")).findFirst().orElseThrow();
        assertThat(read.metadata()).containsEntry(ToolSpec.META_TAGS, List.of("files"));
    }
}
