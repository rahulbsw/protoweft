package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stateless Streamable HTTP tool server: it keeps no session, so any instance can serve any
 * request (no sticky sessions). Our own Streamable HTTP client still drives it end to end.
 */
class McpStatelessHttpTest {

    private static ProtoweftServer server;
    private McpClientConnector connector;

    @BeforeAll
    static void startServer() {
        TestAuthoring.Fixture fixture = TestAuthoring.create();
        server = ProtoweftServer.builder().port(0).authenticator(McpHttpEndToEndTest.AUTH)
                .endpoints(McpToolServer.statelessHttp(TestTools.create())
                        .serverInfo("protoweft-stateless-test", "1.0.0")
                        .resources(fixture.resources())
                        .prompts(fixture.prompts()))
                .start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void closeConnector() {
        if (connector != null) {
            connector.close();
        }
    }

    @Test
    void reportsTheStatelessTransportAndItsLimits() {
        ComplianceReport report = McpToolServer.statelessHttp(TestTools.create()).compliance();

        assertThat(report.supported()).contains(McpFeatures.TRANSPORT_STATELESS_HTTP, McpFeatures.TOOLS_LIST,
                        McpFeatures.TOOLS_CALL, McpFeatures.RESOURCES, McpFeatures.PROMPTS, McpFeatures.COMPLETION)
                .doesNotContain(McpFeatures.TRANSPORT_STREAMABLE_HTTP, McpFeatures.PROGRESS, McpFeatures.LOGGING,
                        McpFeatures.RESOURCES_SUBSCRIBE);
        assertThat(report.unsupported()).contains(McpFeatures.TRANSPORT_STREAMABLE_HTTP, McpFeatures.TRANSPORT_STDIO,
                McpFeatures.PROGRESS, McpFeatures.LOGGING, McpFeatures.RESOURCES_SUBSCRIBE);
        assertThat(report.sdkVersion()).isEqualTo("2.1.0");
    }

    @Test
    void issuesNoSessionIdOnInitialize() throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"raw\",\"version\":\"1.0\"}}}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/mcp"))
                        .header("Authorization", "Bearer " + McpHttpEndToEndTest.TOKEN)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(initialize))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // The defining property of the stateless transport: no session is ever assigned.
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
    }

    @Test
    void servesToolsResourcesAndPromptsOverStatelessHttp() {
        connector = client();

        ToolOutcome sum = connector.invoke(ToolInvocation.of("add", Map.of("a", 2, "b", 3)));
        assertThat(sum.text()).isEqualTo("5");

        assertThat(connector.listResources()).extracting(McpResourceInfo::uri).contains("test://static-text");
        assertThat(connector.readResource("test://static-text")).isNotEmpty();

        assertThat(connector.listPrompts()).extracting(McpPromptInfo::name).contains("test_simple_prompt");
        assertThat(connector.getPrompt("whoami_prompt", Map.of()).messages()).isNotEmpty();
    }

    @Test
    void twoIndependentConnectorsEachSucceedWithoutSharedSession() {
        // No session to share: a second, independently started client works just the same.
        try (McpClientConnector first = client(); McpClientConnector second = client()) {
            assertThat(first.invoke(ToolInvocation.of("add", Map.of("a", 1, "b", 1))).text()).isEqualTo("2");
            assertThat(second.invoke(ToolInvocation.of("add", Map.of("a", 4, "b", 4))).text()).isEqualTo("8");
        }
    }

    private static McpClientConnector client() {
        McpClientConnector c = McpClientConnector.http(server.baseUrl()).endpoint("/mcp")
                .credentials((p, t) -> List.of(new Credential.BearerToken(McpHttpEndToEndTest.TOKEN)))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        c.start();
        return c;
    }
}
