package io.github.rahulbsw.protoweft.conformance.mcp.client;

import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The non-auth client scenarios required for 2025-11-25 ({@code src/scenarios/client/*.ts}).
 * Each opens a fresh {@link McpClientConnector}, does what the scenario's mock server expects,
 * and closes it.
 */
final class CoreScenarios {

    private CoreScenarios() {
    }

    /** {@code initialize}: the handshake (initialize + notifications/initialized), then close. */
    static void initialize(ConformanceMcpClient.Run run) {
        try (McpClientConnector client = connect(run)) {
            System.out.println("initialized: " + client.compliance().negotiatedRevision());
        }
    }

    /** {@code tools_call}: list tools and call {@code add_numbers} with numeric arguments. */
    static void toolsCall(ConformanceMcpClient.Run run) {
        try (McpClientConnector client = connect(run)) {
            callIfListed(client, "add_numbers", Map.of("a", 5, "b", 3));
        }
    }

    /**
     * {@code elicitation-sep1034-client-defaults}: calls {@code test_client_elicitation_defaults},
     * whose server then sends {@code elicitation/create}. {@link McpClientConnector} declares no
     * elicitation capability and registers no handler (McpFeatures.ELICITATION is unsupported), so
     * the request is refused by the SDK; the scenario is in the baseline.
     */
    static void elicitationDefaults(ConformanceMcpClient.Run run) {
        try (McpClientConnector client = connect(run)) {
            callIfListed(client, "test_client_elicitation_defaults", Map.of());
        }
    }

    /**
     * {@code sse-retry}: calls {@code test_reconnection}; the mock server closes the SSE stream
     * after a priming event with {@code retry}, and answers on the GET stream the client must
     * reopen (honouring {@code retry} and sending {@code Last-Event-ID}). That reconnection is the
     * MCP SDK transport's behavior, which the connector uses unchanged.
     */
    static void sseRetry(ConformanceMcpClient.Run run) {
        try (McpClientConnector client = connect(run)) {
            callIfListed(client, "test_reconnection", Map.of());
        }
    }

    static void callIfListed(McpClientConnector client, String tool, Map<String, Object> arguments) {
        List<ToolSpec> tools = client.listTools();
        System.out.println("tools: " + tools.stream().map(ToolSpec::name).toList());
        if (tools.stream().noneMatch(t -> t.name().equals(tool))) {
            throw new IllegalStateException("the server does not list " + tool);
        }
        ToolOutcome outcome = client.invoke(ToolInvocation.of(tool, arguments));
        System.out.println(tool + " -> error=" + outcome.error() + " text=" + outcome.text());
    }

    /** A started connector for the suite's URL: base (scheme, host, port) plus the endpoint path. */
    static McpClientConnector connect(ConformanceMcpClient.Run run) {
        return connect(run, null);
    }

    /** As {@link #connect(ConformanceMcpClient.Run)}, with tokens from {@code authorizer} when it is not null. */
    static McpClientConnector connect(ConformanceMcpClient.Run run, HttpAuthorizer authorizer) {
        McpClientConnector.HttpBuilder builder = McpClientConnector.http(base(run.serverUrl()))
                .endpoint(endpoint(run.serverUrl()))
                .clientInfo("protoweft-conformance-client", "1.0.0")
                .requestTimeout(Duration.ofSeconds(20));
        if (authorizer != null) {
            builder.authorizer(authorizer);
        }
        McpClientConnector client = builder.build();
        try {
            client.start();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    static String base(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getRawAuthority();
    }

    static String endpoint(String url) {
        URI u = URI.create(url);
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
    }
}
