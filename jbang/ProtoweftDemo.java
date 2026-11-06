///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS io.github.rahulbsw:protoweft-bom:${protoweft.version:0.1.0}@pom
//DEPS io.github.rahulbsw:protoweft-mcp
//DEPS io.github.rahulbsw:protoweft-utcp
//DEPS io.github.rahulbsw:protoweft-a2a
//DEPS io.github.rahulbsw:protoweft-server-jetty
//DEPS org.slf4j:slf4j-nop:2.0.17
//JAVAC_OPTIONS -parameters

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.ProtocolConnector;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;
import io.github.rahulbsw.protoweft.mcp.McpToolServer;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import io.github.rahulbsw.protoweft.utcp.UtcpClientConnector;
import io.github.rahulbsw.protoweft.utcp.UtcpManualProvider;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

/**
 * Zero-setup demo: `jbang protoweft-demo@rahulbsw/protoweft`.
 * Serves one Java method over MCP, UTCP and A2A, then calls it back through all three.
 */
public class ProtoweftDemo {

    public static class Weather {
        @AgentTool(description = "Weather forecast for a city", readOnly = true)
        public String forecast(String city) {
            return "Sunny, 21.5 °C in " + city;
        }
    }

    public static void main(String[] args) throws Exception {
        LocalTools tools = LocalTools.builder().scan(new Weather()).build();
        AgentHandler agent = (request, task) -> task.complete(
                tools.invoke(ToolInvocation.of("forecast", Map.of("city", request.text()))).text());
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String base = "http://127.0.0.1:" + port;
        AgentDescriptor card = new AgentDescriptor("Weather agent", "Forecasts by city", "1.0.0", List.of());

        try (A2aAgentServer a2aServer = A2aAgentServer.builder(agent, card).publicUrl(base).build();
             ProtoweftServer server = ProtoweftServer.builder().host("127.0.0.1").port(port)
                     .endpoints(McpToolServer.http(tools), UtcpManualProvider.builder(tools).baseUrl(base).build(),
                             a2aServer)
                     .start();
             ToolRegistry registry = new ToolRegistry()) {
            McpClientConnector mcp = McpClientConnector.http(base).build();
            UtcpClientConnector utcp = UtcpClientConnector.http("weather", base + "/utcp").build();
            A2aAgentConnector a2a = A2aAgentConnector.builder(base).build();
            for (ProtocolConnector c : List.<ProtocolConnector>of(mcp, utcp, a2a)) {
                c.start();
            }
            registry.register("mcp", mcp).register("utcp", utcp).register("a2a", a2a);
            String city = args.length > 0 ? args[0] : "Oslo";
            System.out.println("mcp.forecast  -> " + registry.invoke(ToolInvocation.of("mcp.forecast", Map.of("city", city))).text());
            System.out.println("utcp.forecast -> " + registry.invoke(ToolInvocation.of("utcp.forecast",
                    Map.of("body", Map.of("city", city)))).text());
            System.out.println("a2a.ask       -> " + registry.invoke(ToolInvocation.of("a2a.ask", Map.of("message", city))).text());
        }
    }
}
