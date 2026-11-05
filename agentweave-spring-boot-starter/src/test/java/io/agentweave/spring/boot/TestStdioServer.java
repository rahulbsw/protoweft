package io.agentweave.spring.boot;

import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.mcp.McpToolServer;

import java.util.Map;

/** An stdio MCP server (one {@code echo} tool), launched as a subprocess by the tests. */
public final class TestStdioServer {

    public static void main(String[] args) {
        LocalTools tools = LocalTools.builder()
                .tool("echo", "Echoes the message",
                        Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))),
                        (arguments, caller) -> ToolOutcome.text(String.valueOf(arguments.get("message"))))
                .build();
        McpToolServer.stdio(tools).serverInfo("test-stdio", "1.0.0").run();
    }

    /** Properties declaring this server as MCP client {@code name}. */
    static String[] clientProperties(String name) {
        String prefix = "agentweave.mcp.clients." + name + ".";
        return new String[] {
                prefix + "command=" + System.getProperty("java.home") + "/bin/java",
                prefix + "args[0]=-cp",
                prefix + "args[1]=" + System.getProperty("agentweave.test.classpath"),
                prefix + "args[2]=" + TestStdioServer.class.getName()
        };
    }
}
