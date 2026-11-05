package io.agentweave.mcp;

import io.agentweave.server.jetty.AgentWeaveServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SDK's own Streamable HTTP client against our server, so interoperability does not
 * rest on our client agreeing with our server.
 */
class SdkClientInteropTest {

    private static AgentWeaveServer server;
    private McpSyncClient client;

    @BeforeAll
    static void startServer() {
        server = AgentWeaveServer.builder()
                .port(0)
                .authenticator(McpHttpEndToEndTest.AUTH)
                .endpoints(McpToolServer.http(TestTools.create()).serverInfo("agentweave-interop", "2.0.0"))
                .start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void connect() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(server.baseUrl())
                .addHttpRequestCustomizer((request, method, uri, body, context) ->
                        request.setHeader("Authorization", "Bearer " + McpHttpEndToEndTest.TOKEN))
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void initializeAdvertisesToolsAndServerInfo() {
        McpSchema.InitializeResult init = client.initialize();

        assertThat(init.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(init.serverInfo().name()).isEqualTo("agentweave-interop");
        assertThat(init.serverInfo().version()).isEqualTo("2.0.0");
        assertThat(init.capabilities().tools()).isNotNull();
        assertThat(init.capabilities().resources()).isNull();
        assertThat(init.capabilities().prompts()).isNull();
    }

    @Test
    void listsAndCallsToolsWithStructuredContent() {
        client.initialize();

        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("echo", "add", "whoami");

        McpSchema.CallToolResult sum = client.callTool(
                McpSchema.CallToolRequest.builder("add").arguments(Map.of("a", 40, "b", 2)).build());
        assertThat(sum.isError()).isFalse();
        assertThat(sum.content()).singleElement().isInstanceOfSatisfying(McpSchema.TextContent.class,
                t -> assertThat(t.text()).isEqualTo("42"));
        assertThat(sum.structuredContent()).isEqualTo(Map.of("sum", 42));
    }

    @Test
    void callerIdentityComesFromTheHostAuthenticator() {
        client.initialize();

        McpSchema.CallToolResult who = client.callTool(McpSchema.CallToolRequest.builder("whoami").build());

        assertThat(((McpSchema.TextContent) who.content().get(0)).text()).isEqualTo("alice");
    }

    @Test
    void toolErrorsUseIsErrorAndUnknownToolsUseInvalidParams() {
        client.initialize();

        McpSchema.CallToolResult failed = client.callTool(McpSchema.CallToolRequest.builder("fail").build());
        assertThat(failed.isError()).isTrue();

        assertThatThrownBy(() -> client.callTool(McpSchema.CallToolRequest.builder("nope").build()))
                .isInstanceOfSatisfying(McpError.class,
                        e -> assertThat(e.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
    }
}
