package io.agentweave.conformance.mcp.client;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extension point for the suite's {@code auth/*} client scenarios (metadata-default/var1-3,
 * basic-cimd, scope-*, token-endpoint-auth-*, pre-registration). None is implemented yet:
 * {@code agentweave-mcp} has no MCP authorization flow ({@code McpFeatures.AUTHORIZATION} is
 * unsupported), and these scenarios are not run by the scripts or CI.
 *
 * <p>To add them once OAuth lands: register each scenario in {@link #SCENARIOS}; each builds a
 * connector with the OAuth client ({@code McpClientConnector.http(..).oauth(..)}), reading
 * {@code client_id}/{@code client_secret} for {@code auth/pre-registration} from
 * {@link ConformanceMcpClient.Run#contextJson()}, then calls a tool so the 401 → discovery →
 * token → retry flow runs. Then add the names to the CI matrix and the client script, and drop
 * any baseline entries they make stale.
 */
final class AuthScenarios {

    /** Implemented auth scenarios by name, for example {@code "auth/metadata-default"}. Empty for now. */
    static final Map<String, ConformanceMcpClient.Scenario> SCENARIOS = new TreeMap<>();

    private AuthScenarios() {
    }

    /** The handler for {@code scenario}, or one that fails naming the missing feature. */
    static ConformanceMcpClient.Scenario find(String scenario) {
        ConformanceMcpClient.Scenario s = SCENARIOS.get(scenario);
        if (s != null) {
            return s;
        }
        return run -> {
            throw new UnsupportedOperationException(scenario + " needs MCP authorization, which agentweave-mcp "
                    + "does not implement yet (McpFeatures.AUTHORIZATION)");
        };
    }

    static Set<String> implemented() {
        return SCENARIOS.keySet();
    }
}
