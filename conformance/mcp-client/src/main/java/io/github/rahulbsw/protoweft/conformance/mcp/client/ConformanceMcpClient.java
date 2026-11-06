package io.github.rahulbsw.protoweft.conformance.mcp.client;

import java.util.Map;
import java.util.TreeMap;

/**
 * The client under test for the MCP conformance suite's client scenarios, built on Protoweft's
 * {@link io.github.rahulbsw.protoweft.mcp.McpClientConnector}, with protoweft-oauth's {@code OAuthClient} as its
 * authorizer for the {@code auth/*} scenarios.
 *
 * <p>Contract (from the suite's {@code src/runner/client.ts}): the server URL is the last
 * command-line argument; {@code MCP_CONFORMANCE_SCENARIO} names the scenario,
 * {@code MCP_CONFORMANCE_PROTOCOL_VERSION} the spec version under test, and
 * {@code MCP_CONFORMANCE_CONTEXT} (optional) carries scenario data as JSON. Exit code 0 means
 * the client finished its side of the scenario; the suite judges the result from the wire.
 *
 * <p>Scenarios are registered in {@link #SCENARIOS}. The {@code auth/*} scenarios are routed to
 * {@link AuthScenarios} (MCP authorization).
 */
public final class ConformanceMcpClient {

    /** One client scenario: runs against the suite's mock server and returns normally on success. */
    @FunctionalInterface
    interface Scenario {
        void run(Run run) throws Exception;
    }

    /**
     * One invocation by the suite.
     *
     * @param serverUrl       the mock server's MCP endpoint URL
     * @param scenario        the scenario name
     * @param protocolVersion the spec version under test, or {@code null}
     * @param contextJson     the raw {@code MCP_CONFORMANCE_CONTEXT} JSON, or {@code null}
     */
    record Run(String serverUrl, String scenario, String protocolVersion, String contextJson) {
    }

    static final Map<String, Scenario> SCENARIOS = new TreeMap<>(Map.of(
            "initialize", CoreScenarios::initialize,
            "tools_call", CoreScenarios::toolsCall,
            "elicitation-sep1034-client-defaults", CoreScenarios::elicitationDefaults,
            "sse-retry", CoreScenarios::sseRetry));

    private ConformanceMcpClient() {
    }

    public static void main(String[] args) {
        String scenario = System.getenv("MCP_CONFORMANCE_SCENARIO");
        if (args.length == 0 || scenario == null || scenario.isBlank()) {
            System.err.println("usage: MCP_CONFORMANCE_SCENARIO=<scenario> ConformanceMcpClient <server-url>");
            System.exit(2);
        }
        Run run = new Run(args[args.length - 1], scenario, System.getenv("MCP_CONFORMANCE_PROTOCOL_VERSION"),
                System.getenv("MCP_CONFORMANCE_CONTEXT"));
        Scenario handler = scenario.startsWith("auth/") ? AuthScenarios.find(scenario) : SCENARIOS.get(scenario);
        if (handler == null) {
            System.err.println("unknown scenario: " + scenario + "; implemented: " + SCENARIOS.keySet()
                    + " and " + AuthScenarios.implemented());
            System.exit(2);
        }
        try {
            handler.run(run);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("scenario " + scenario + " failed: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
}
