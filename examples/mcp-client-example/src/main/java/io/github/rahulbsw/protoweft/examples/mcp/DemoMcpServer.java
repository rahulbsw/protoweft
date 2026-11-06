package io.github.rahulbsw.protoweft.examples.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** A one-tool stdio MCP server built on the official SDK, so the example runs without Node. */
public final class DemoMcpServer {

    public static void main(String[] args) throws InterruptedException {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("name", Map.of("type", "string")), "required", java.util.List.of("name"));
        SyncToolSpecification greet = SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("greet", schema).description("Greets someone by name").build())
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .addTextContent("Hello, " + request.arguments().get("name") + "!")
                        .build())
                .build();

        McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("protoweft-demo-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(greet)
                .build();
        new CountDownLatch(1).await(); // the client terminates this process on close
    }
}
