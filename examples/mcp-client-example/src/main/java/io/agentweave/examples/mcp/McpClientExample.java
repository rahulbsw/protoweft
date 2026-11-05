package io.agentweave.examples.mcp;

import io.agentweave.core.ComplianceReport;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.mcp.McpClientConnector;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Connects to an MCP server over stdio and uses it only through the protocol-neutral
 * {@link ToolConnector} API.
 *
 * <p>With no arguments it launches the bundled {@link DemoMcpServer} and calls its
 * {@code greet} tool. With arguments, they are the server command line, for example
 * {@code npx -y @modelcontextprotocol/server-everything}, and the example lists its tools.
 */
public final class McpClientExample {

    public static void main(String[] args) {
        boolean demo = args.length == 0;
        McpClientConnector.Builder builder = demo
                ? McpClientConnector.stdio(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        DemoMcpServer.class.getName())
                : McpClientConnector.stdio(args[0], java.util.Arrays.copyOfRange(args, 1, args.length));

        try (ToolConnector tools = builder.observations(new ConsoleObservations()).build()) {
            tools.start();
            printCompliance(tools.compliance());

            System.out.println("Tools:");
            for (ToolSpec tool : tools.listTools()) {
                System.out.println("  - " + tool.name() + ": " + tool.description());
            }

            if (demo) {
                String reply = tools.invoke(ToolInvocation.of("greet", Map.of("name", "AgentWeave"))).text();
                System.out.println("greet -> " + reply);
            }
        }
    }

    private static void printCompliance(ComplianceReport r) {
        System.out.println("Protocol:          " + r.protocol());
        System.out.println("SDK:               " + r.sdkName() + " " + r.sdkVersion());
        System.out.println("Wire revisions:    " + r.wireRevisions());
        System.out.println("Negotiated:        " + r.negotiated().orElse("-"));
        System.out.println("Supported:         " + r.supported().stream().sorted().toList());
        System.out.println("Not implemented:   " + r.unsupported().stream().sorted().toList());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Shows the observability hook: prints each protocol operation and its latency. */
    private static final class ConsoleObservations implements ObservationHandler {
        @Override
        public void onSuccess(Observation o, Duration elapsed) {
            System.out.printf("  [observe] %s %s ok in %d ms%n", o.operation(), o.target(), elapsed.toMillis());
        }

        @Override
        public void onError(Observation o, Duration elapsed, Throwable error) {
            System.out.printf("  [observe] %s %s failed: %s%n", o.operation(), o.target(), error.getMessage());
        }
    }
}
