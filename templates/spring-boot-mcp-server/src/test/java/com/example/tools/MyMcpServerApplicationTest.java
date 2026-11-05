package com.example.tools;

import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.mcp.McpClientConnector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the app and calls its tools over MCP, the way any MCP client would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MyMcpServerApplicationTest {

    @LocalServerPort
    int port;

    @Test
    void servesToolsOverMcp() {
        try (McpClientConnector mcp = McpClientConnector.http("http://localhost:" + port).build()) {
            mcp.start();
            assertThat(mcp.listTools()).extracting(t -> t.name()).contains("greet", "add");
            assertThat(mcp.invoke(ToolInvocation.of("greet", Map.of("name", "Ada"))).text()).isEqualTo("Hello, Ada!");
        }
    }
}
