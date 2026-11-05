package io.agentweave.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A real stdio MCP server built on the official SDK, launched as a subprocess by the tests.
 * Pass {@code --no-tools} to start a server that does not declare the tools capability.
 */
public final class TestMcpServer {

    public static void main(String[] args) throws InterruptedException {
        boolean noTools = List.of(args).contains("--no-tools");
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        var spec = McpServer.sync(transport).serverInfo("agentweave-test-server", "1.0.0");
        if (noTools) {
            spec.capabilities(McpSchema.ServerCapabilities.builder().build()).build();
        } else {
            spec.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(echo(), add(), fail(), env())
                    .build();
        }
        new CountDownLatch(1).await(); // the client terminates this process on close
    }

    private static SyncToolSpecification echo() {
        return tool("echo", "Echoes the message", Map.of("message", Map.of("type", "string")),
                args -> McpSchema.CallToolResult.builder().addTextContent(String.valueOf(args.get("message"))).build());
    }

    private static SyncToolSpecification add() {
        return tool("add", "Adds two integers", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
                args -> {
                    long sum = ((Number) args.get("a")).longValue() + ((Number) args.get("b")).longValue();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(Long.toString(sum))
                            .structuredContent(Map.of("sum", sum))
                            .build();
                });
    }

    private static SyncToolSpecification fail() {
        return tool("fail", "Always reports a tool error", Map.of(),
                args -> McpSchema.CallToolResult.builder().addTextContent("it broke").isError(true).build());
    }

    private static SyncToolSpecification env() {
        return tool("env", "Returns an environment variable", Map.of("name", Map.of("type", "string")),
                args -> McpSchema.CallToolResult.builder()
                        .addTextContent(String.valueOf(System.getenv(String.valueOf(args.get("name")))))
                        .build());
    }

    private static SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                              java.util.function.Function<Map<String, Object>, McpSchema.CallToolResult> body) {
        Map<String, Object> schema = Map.of("type", "object", "properties", properties);
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, schema).description(description).build())
                .callHandler((exchange, request) -> body.apply(request.arguments() == null ? Map.of() : request.arguments()))
                .build();
    }
}
