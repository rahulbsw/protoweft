package io.github.rahulbsw.protoweft.examples.gateway;

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolConnector;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;
import io.github.rahulbsw.protoweft.mcp.McpToolServer;
import io.github.rahulbsw.protoweft.rap.RapCallContext;
import io.github.rahulbsw.protoweft.rap.RapCallbackEndpoint;
import io.github.rahulbsw.protoweft.rap.RapClientConnector;
import io.github.rahulbsw.protoweft.rap.RapSubscription;
import io.github.rahulbsw.protoweft.rap.RapToolServer;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import io.github.rahulbsw.protoweft.utcp.UtcpClientConnector;
import io.github.rahulbsw.protoweft.utcp.UtcpManualProvider;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Defines one tool and one agent in plain Java, serves them over MCP, UTCP, A2A and RAP from a
 * single embedded server, then consumes all four back through one {@link ToolRegistry}. Over RAP
 * it also serves a subscription tool and prints the events it streams.
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

    /** A RAP subscription tool: answers at once, then streams updates through {@link RapCallContext}. */
    public static class ForecastUpdates {
        @AgentTool(description = "Streams forecast updates for a city", readOnly = true, tags = {"weather"})
        public String updates(@ToolParam(description = "City name") String city, RapCallContext rap) {
            if (!rap.available()) {
                return "Updates for " + city + " need a RAP subscription";   // called over another protocol
            }
            RapSubscription subscription = rap.subscribe();
            Thread producer = new Thread(() -> {
                subscription.emit(city + ": 22.0 C, sunny");
                subscription.emit(city + ": 19.5 C, showers", true);   // associative: belongs inline in the thread
                subscription.close(city + ": no more updates", false); // the final event
            }, "forecast-updates");
            producer.setDaemon(true);
            producer.start();
            return "Watching " + city;
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

        // RAP answers asynchronously: tool servers POST results and events back to the caller, here to a
        // callback endpoint mounted on the same server. The RAP server adds the subscription tool.
        RapCallbackEndpoint rapCallbacks = RapCallbackEndpoint.create(base);
        LocalTools rapTools = LocalTools.builder().scan(new Weather()).scan(new ForecastUpdates()).build();

        try (A2aAgentServer a2aServer = A2aAgentServer.builder(agent, descriptor).publicUrl(base).build();
             RapToolServer rapServer = RapToolServer.builder(rapTools).name("weather").basePath("/rap").build();
             ProtoweftServer server = ProtoweftServer.builder().host("127.0.0.1").port(port)
                     .endpoints(McpToolServer.http(tools),
                             UtcpManualProvider.builder(tools).baseUrl(base).build(),
                             a2aServer,
                             rapServer,
                             rapCallbacks)
                     .start();
             ToolRegistry registry = new ToolRegistry()) {
            System.out.println("Serving MCP at " + base + "/mcp, UTCP at " + base + "/utcp, A2A at " + base
                    + ", RAP at " + base + "/rap");

            McpClientConnector mcp = McpClientConnector.http(base).build();
            UtcpClientConnector utcp = UtcpClientConnector.http("weather", base + "/utcp").build();
            A2aAgentConnector a2a = A2aAgentConnector.builder(base).build();
            RapClientConnector rap = RapClientConnector.builder(base + "/rap").callbacks(rapCallbacks)
                    .timeout(Duration.ofSeconds(30)).build();
            for (ProtocolConnector c : List.of(mcp, utcp, a2a, rap)) {
                c.start();
            }
            registry.register("via_mcp", mcp).register("via_utcp", utcp).register("via_a2a", a2a)
                    .register("via_rap", rap);

            System.out.println("\nOne registry, four protocols:");
            for (ToolSpec spec : registry.listTools()) {
                System.out.println("  - " + spec.name() + ": " + spec.description().lines().findFirst().orElse(""));
            }

            System.out.println("\nCalls:");
            print(registry, "via_mcp.forecast", Map.of("city", "Oslo"));
            // UTCP tools served by Protoweft take their arguments as one JSON body.
            print(registry, "via_utcp.forecast", Map.of("body", Map.of("city", "Oslo")));
            print(registry, "via_a2a.ask", Map.of("message", "Oslo"));
            print(registry, "via_rap.forecast", Map.of("city", "Oslo"));

            System.out.println("\nRAP subscription:");
            ToolOutcome watching = registry.invoke(ToolInvocation.of("via_rap.updates", Map.of("city", "Oslo")));
            System.out.printf("  %-18s -> %s%n", "via_rap.updates", watching.text());
            for (ToolEvent event : collect(watching)) {
                System.out.println("    event: " + event.text() + (event.last() ? " (final)"
                        : Boolean.TRUE.equals(event.metadata().get("associative")) ? " (associative)" : ""));
            }

            System.out.println("\nCompliance:");
            for (ProtocolConnector c : List.of(mcp, utcp, a2a, rap)) {
                ComplianceReport r = c.compliance();
                System.out.printf("  %-5s sdk=%s %s  wire=%s negotiated=%s%n", r.protocol(), r.sdkName(),
                        r.sdkVersion(), r.wireRevisions(), r.negotiated().orElse("-"));
            }
        }
    }

    /** Every event of the outcome's subscription, once the tool has sent its final event. */
    private static List<ToolEvent> collect(ToolOutcome outcome) {
        List<ToolEvent> events = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        outcome.subscription().orElseThrow().events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ToolEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.orTimeout(30, TimeUnit.SECONDS).join();
        return events;
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
