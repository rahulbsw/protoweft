package com.example.client;

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;
import io.github.rahulbsw.protoweft.utcp.UtcpClientConnector;

import java.util.Map;

/**
 * Connects to whatever you pass on the command line and lists every tool through one registry:
 *
 * <pre>
 *   ./gradlew run --args="mcp=http://localhost:8080"
 *   ./gradlew run --args="mcp-stdio=npx,-y,@modelcontextprotocol/server-everything"
 *   ./gradlew run --args="utcp=https://api.example.com/utcp a2a=https://agent.example.com"
 *   ./gradlew run --args="mcp=http://localhost:8080 call=mcp.greet name=Ada"
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        try (ToolRegistry registry = new ToolRegistry()) {
            String call = null;
            Map<String, Object> callArgs = new java.util.LinkedHashMap<>();
            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                switch (kv[0]) {
                    case "mcp" -> registry.register("mcp", started(McpClientConnector.http(kv[1]).build()));
                    case "mcp-stdio" -> {
                        String[] cmd = kv[1].split(",");
                        registry.register("mcp", started(McpClientConnector.stdio(cmd[0],
                                java.util.Arrays.copyOfRange(cmd, 1, cmd.length)).build()));
                    }
                    case "utcp" -> registry.register("utcp", started(UtcpClientConnector.http("utcp", kv[1]).build()));
                    case "a2a" -> registry.register("agent", started(A2aAgentConnector.builder(kv[1]).build()));
                    case "call" -> call = kv[1];
                    default -> callArgs.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }
            for (ToolSpec tool : registry.listTools()) {
                System.out.println(tool.name() + " — " + tool.description().lines().findFirst().orElse(""));
            }
            if (call != null) {
                System.out.println("\n" + call + " -> " + registry.invoke(ToolInvocation.of(call, callArgs)).text());
            }
        }
    }

    private static <T extends io.github.rahulbsw.protoweft.core.ProtocolConnector> T started(T connector) {
        connector.start();
        return connector;
    }
}
