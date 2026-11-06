package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SDK's own client against our server publishing the MCP conformance suite's fixtures
 * ({@link TestAuthoring}), so interoperability of resources, prompts, completion, logging and
 * progress does not rest on our client agreeing with our server.
 */
class ConformanceFixtureTest {

    private static ProtoweftServer server;
    private final BlockingQueue<List<McpSchema.ResourceContents>> updates = new LinkedBlockingQueue<>();
    private final List<McpSchema.LoggingMessageNotification> logs = new CopyOnWriteArrayList<>();
    private final List<McpSchema.ProgressNotification> progress = new CopyOnWriteArrayList<>();
    private McpSyncClient client;

    @BeforeAll
    static void startServer() {
        TestAuthoring.Fixture fixture = TestAuthoring.create();
        server = ProtoweftServer.builder().port(0).authenticator(McpHttpEndToEndTest.AUTH)
                .endpoints(McpToolServer.http(fixture.tools()).resources(fixture.resources()).prompts(fixture.prompts()))
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
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10))
                .resourcesUpdateConsumer(updates::add)
                .loggingConsumer(logs::add)
                .progressConsumer(progress::add)
                .build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void declaresExactlyTheCapabilitiesItServes() {
        McpSchema.ServerCapabilities caps = client.getServerCapabilities();

        assertThat(caps.tools()).isEqualTo(new McpSchema.ServerCapabilities.ToolCapabilities(false));
        assertThat(caps.resources()).isEqualTo(new McpSchema.ServerCapabilities.ResourceCapabilities(true, false));
        assertThat(caps.prompts()).isEqualTo(new McpSchema.ServerCapabilities.PromptCapabilities(false));
        assertThat(caps.logging()).isNotNull();
        assertThat(caps.completions()).isNotNull();
    }

    @Test
    void servesTheFixtureResources() {
        assertThat(client.listResources().resources()).extracting(McpSchema.Resource::uri)
                .contains("test://static-text", "test://static-binary", "test://watched-resource");
        assertThat(client.listResourceTemplates().resourceTemplates()).extracting(McpSchema.ResourceTemplate::uriTemplate)
                .contains("test://template/{id}/data");

        assertThat(read("test://static-text")).isInstanceOfSatisfying(McpSchema.TextResourceContents.class, t -> {
            assertThat(t.text()).isEqualTo(TestAuthoring.STATIC_TEXT);
            assertThat(t.mimeType()).isEqualTo("text/plain");
        });
        assertThat(read("test://static-binary")).isInstanceOfSatisfying(McpSchema.BlobResourceContents.class, b -> {
            assertThat(b.blob()).isEqualTo(TestTools.PNG);
            assertThat(b.mimeType()).isEqualTo("image/png");
        });
        assertThat(read("test://template/123/data")).isInstanceOfSatisfying(McpSchema.TextResourceContents.class,
                t -> assertThat(t.text()).contains("123"));
    }

    @Test
    void subscriptionsReceiveUpdates() throws Exception {
        client.subscribeResource(McpSchema.SubscribeRequest.builder("test://watched-resource").build());
        client.callTool(McpSchema.CallToolRequest.builder("touch").build());

        List<McpSchema.ResourceContents> update = updates.poll(10, TimeUnit.SECONDS);
        assertThat(update).singleElement().extracting(McpSchema.ResourceContents::uri)
                .isEqualTo("test://watched-resource");

        client.unsubscribeResource(McpSchema.UnsubscribeRequest.builder("test://watched-resource").build());
        client.callTool(McpSchema.CallToolRequest.builder("touch").build());
        assertThat(updates.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void servesTheFixturePrompts() {
        assertThat(client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).contains("test_simple_prompt",
                "test_prompt_with_arguments", "test_prompt_with_embedded_resource", "test_prompt_with_image");

        assertThat(prompt("test_simple_prompt", Map.of()).get(0).content())
                .isInstanceOf(McpSchema.TextContent.class);
        assertThat(prompt("test_prompt_with_arguments", Map.of("arg1", "x", "arg2", "y")).get(0).content())
                .isInstanceOfSatisfying(McpSchema.TextContent.class,
                        t -> assertThat(t.text()).contains("arg1='x'").contains("arg2='y'"));
        assertThat(prompt("test_prompt_with_embedded_resource", Map.of("resourceUri", "test://example")).get(0)
                .content()).isInstanceOfSatisfying(McpSchema.EmbeddedResource.class, e -> {
                    assertThat(e.resource().uri()).isEqualTo("test://example");
                    assertThat(((McpSchema.TextResourceContents) e.resource()).text())
                            .isEqualTo(TestAuthoring.EMBEDDED_TEXT);
                });
        List<McpSchema.PromptMessage> image = prompt("test_prompt_with_image", Map.of());
        assertThat(image.get(0).role()).isEqualTo(McpSchema.Role.USER);
        assertThat(image.get(0).content()).isInstanceOfSatisfying(McpSchema.ImageContent.class, i -> {
            assertThat(i.data()).isEqualTo(TestTools.PNG);
            assertThat(i.mimeType()).isEqualTo("image/png");
        });
    }

    @Test
    void completesPromptArgumentsAndTemplateVariables() {
        McpSchema.CompleteResult prompt = client.completeCompletion(new McpSchema.CompleteRequest(
                new McpSchema.PromptReference("test_prompt_with_arguments"),
                new McpSchema.CompleteRequest.CompleteArgument("arg1", "a"), null, null));
        assertThat(prompt.completion().values()).containsExactly("alpha", "alps");
        assertThat(prompt.completion().total()).isEqualTo(2);
        assertThat(prompt.completion().hasMore()).isFalse();

        McpSchema.CompleteResult template = client.completeCompletion(new McpSchema.CompleteRequest(
                new McpSchema.ResourceReference("test://template/{id}/data"),
                new McpSchema.CompleteRequest.CompleteArgument("id", "1"), null, null));
        assertThat(template.completion().values()).containsExactly("1", "10");
    }

    @Test
    void progressNotificationsCarryTheRequestToken() throws Exception {
        client.callTool(McpSchema.CallToolRequest.builder("test_tool_with_progress").progressToken("tok-7").build());

        awaitSize(progress, 3);
        assertThat(progress).allSatisfy(p -> {
            assertThat(p.progressToken()).isEqualTo("tok-7");
            assertThat(p.total()).isEqualTo(100.0);
        });
        // The SDK's sync client delivers each notification on its own thread, so order isn't asserted.
        assertThat(progress).extracting(McpSchema.ProgressNotification::progress)
                .containsExactlyInAnyOrder(0.0, 50.0, 100.0);

        progress.clear();
        client.callTool(McpSchema.CallToolRequest.builder("test_tool_with_progress").build());
        Thread.sleep(300);
        assertThat(progress).isEmpty();
    }

    @Test
    void logMessagesFollowTheLevelTheClientSets() throws Exception {
        client.setLoggingLevel(McpSchema.LoggingLevel.DEBUG);
        client.callTool(McpSchema.CallToolRequest.builder("test_tool_with_logging").build());
        awaitSize(logs, 3);
        assertThat(logs).extracting(McpSchema.LoggingMessageNotification::level).containsExactlyInAnyOrder(
                McpSchema.LoggingLevel.DEBUG, McpSchema.LoggingLevel.INFO, McpSchema.LoggingLevel.WARNING);

        logs.clear();
        client.setLoggingLevel(McpSchema.LoggingLevel.ERROR);
        client.callTool(McpSchema.CallToolRequest.builder("test_tool_with_logging").build());
        Thread.sleep(300);
        assertThat(logs).isEmpty();
    }

    private McpSchema.ResourceContents read(String uri) {
        return client.readResource(McpSchema.ReadResourceRequest.builder(uri).build()).contents().get(0);
    }

    private List<McpSchema.PromptMessage> prompt(String name, Map<String, Object> args) {
        return client.getPrompt(McpSchema.GetPromptRequest.builder(name).arguments(args).build()).messages();
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (list.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        assertThat(list).hasSize(size);
    }
}
