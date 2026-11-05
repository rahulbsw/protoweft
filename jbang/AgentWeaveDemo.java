///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS io.agentweave:agentweave-bom:${agentweave.version:0.1.0}@pom
//DEPS io.agentweave:agentweave-mcp
//DEPS io.agentweave:agentweave-utcp
//DEPS io.agentweave:agentweave-a2a
//DEPS io.agentweave:agentweave-server-jetty
//DEPS org.slf4j:slf4j-nop:2.0.17
//JAVAC_OPTIONS -parameters

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.a2a.A2aAgentServer;
import io.agentweave.core.ProtocolConnector;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.mcp.McpClientConnector;
import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.jetty.AgentWeaveServer;
import io.agentweave.utcp.UtcpClientConnector;
import io.agentweave.utcp.UtcpManualProvider;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

/**
 * Zero-setup demo: `jbang agentweave-demo@rahulbsw/agentweave`.
 * Serves one Java method over MCP, UTCP and A2A, then calls it back through all three.
 */
public class AgentWeaveDemo {

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
             AgentWeaveServer server = AgentWeaveServer.builder().host("127.0.0.1").port(port)
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
