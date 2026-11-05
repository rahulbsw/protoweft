package io.agentweave.examples.gateway;

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.a2a.A2aAgentServer;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ProtocolConnector;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolParam;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.mcp.McpClientConnector;
import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.jetty.AgentWeaveServer;
import io.agentweave.utcp.UtcpClientConnector;
import io.agentweave.utcp.UtcpManualProvider;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

/**
 * Defines one tool and one agent in plain Java, serves them over MCP, UTCP and A2A from a
 * single embedded server, then consumes all three back through one {@link ToolRegistry}.
 */
public final class GatewayExample {

    /** The one tool definition, served over every protocol. */
    public static class Weather {
        public record Forecast(String city, double celsius, String summary) {
        }

        @AgentTool(description = "Weather forecast for a city", readOnly = true, tags = {"weather"})
        public Forecast forecast(@ToolParam(description = "City name") String city) {
            return new Forecast(city, 21.5, "Sunny");
        }
    }

    public static void main(String[] args) throws IOException {
        LocalTools tools = LocalTools.builder().scan(new Weather()).build();

        // An agent that answers a city name with a forecast artifact.
        AgentHandler agent = (request, task) -> {
            task.working("Looking up " + request.text());
            var outcome = tools.invoke(ToolInvocation.of("forecast", Map.of("city", request.text())));
            task.artifact("forecast", outcome.content());
            task.complete();
        };
        AgentDescriptor descriptor = new AgentDescriptor("Weather agent", "Answers weather questions by city",
                "1.0.0", List.of(new AgentDescriptor.Skill("forecast", "Forecast", "Forecast for a city",
                List.of("weather"), List.of("Oslo"))));

        String base = "http://127.0.0.1:" + freePort();
        int port = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));

        try (A2aAgentServer a2aServer = A2aAgentServer.builder(agent, descriptor).publicUrl(base).build();
             AgentWeaveServer server = AgentWeaveServer.builder().host("127.0.0.1").port(port)
                     .endpoints(McpToolServer.http(tools),
                             UtcpManualProvider.builder(tools).baseUrl(base).build(),
                             a2aServer)
                     .start();
             ToolRegistry registry = new ToolRegistry()) {
            System.out.println("Serving MCP at " + base + "/mcp, UTCP at " + base + "/utcp, A2A at " + base);

            McpClientConnector mcp = McpClientConnector.http(base).build();
            UtcpClientConnector utcp = UtcpClientConnector.http("weather", base + "/utcp").build();
            A2aAgentConnector a2a = A2aAgentConnector.builder(base).build();
            for (ProtocolConnector c : List.of(mcp, utcp, a2a)) {
                c.start();
            }
            registry.register("via_mcp", mcp).register("via_utcp", utcp).register("via_a2a", a2a);

            System.out.println("\nOne registry, three protocols:");
            for (ToolSpec spec : registry.listTools()) {
                System.out.println("  - " + spec.name() + ": " + spec.description().lines().findFirst().orElse(""));
            }

            System.out.println("\nCalls:");
            print(registry, "via_mcp.forecast", Map.of("city", "Oslo"));
            // UTCP tools served by AgentWeave take their arguments as one JSON body.
            print(registry, "via_utcp.forecast", Map.of("body", Map.of("city", "Oslo")));
            print(registry, "via_a2a.ask", Map.of("message", "Oslo"));

            System.out.println("\nCompliance:");
            for (ProtocolConnector c : List.of(mcp, utcp, a2a)) {
                ComplianceReport r = c.compliance();
                System.out.printf("  %-5s sdk=%s %s  wire=%s negotiated=%s%n", r.protocol(), r.sdkName(),
                        r.sdkVersion(), r.wireRevisions(), r.negotiated().orElse("-"));
            }
        }
    }

    private static void print(ToolRegistry registry, String tool, Map<String, Object> arguments) {
        System.out.printf("  %-18s -> %s%n", tool, registry.invoke(ToolInvocation.of(tool, arguments)).text());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
