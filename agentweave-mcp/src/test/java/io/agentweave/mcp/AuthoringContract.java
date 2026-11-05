package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolProgress;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resources, prompts, completion, logging and progress, end to end through our client against
 * our server serving {@link TestAuthoring}. Each transport runs the same contract.
 */
abstract class AuthoringContract {

    /** A started connector to a server publishing {@link TestAuthoring}. */
    abstract McpClientConnector connector();

    /** The caller name the server's handlers see. */
    abstract String expectedCaller();

    @Test
    void listsAndReadsTextAndBinaryResources() {
        McpClientConnector c = connector();

        assertThat(c.listResources()).extracting(McpResourceInfo::uri).contains("test://static-text",
                "test://static-binary", "test://watched-resource", "notes://today", "notes://raw");
        McpResourceInfo text = c.listResources().stream().filter(r -> r.uri().equals("test://static-text")).findFirst()
                .orElseThrow();
        assertThat(text).isEqualTo(new McpResourceInfo("test://static-text", "static-text", "Static text",
                "A text resource", "text/plain"));

        assertThat(c.readResource("test://static-text")).containsExactly(
                McpResourceContents.text("test://static-text", "text/plain", TestAuthoring.STATIC_TEXT));
        McpResourceContents binary = c.readResource("test://static-binary").get(0);
        assertThat(binary.isText()).isFalse();
        assertThat(binary.mimeType()).isEqualTo("image/png");
        assertThat(binary.bytes()).isEqualTo(Base64.getDecoder().decode(TestTools.PNG));
    }

    @Test
    void readsTemplatedResourcesWithDecodedVariables() {
        McpClientConnector c = connector();

        assertThat(c.listResourceTemplates()).extracting(McpTemplateInfo::uriTemplate)
                .contains("test://template/{id}/data", "users://{user}/profile/{age}");
        McpResourceContents data = c.readResource("test://template/42/data").get(0);
        assertThat(data.uri()).isEqualTo("test://template/42/data");
        assertThat(data.mimeType()).isEqualTo("application/json");
        assertThat(data.text()).contains("\"id\":\"42\"").contains("Data for ID: 42");

        assertThat(c.readResource("users://J%C3%BCrgen/profile/30").get(0).text())
                .isEqualTo("{\"user\":\"Jürgen\",\"age\":30}");
        assertThatThrownBy(() -> c.readResource("users://ann/profile/old"))
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                    assertThat(e.protocolCode()).contains("-32602");
                });
    }

    @Test
    void unknownResourceIsInvalidArgumentsWithResourceNotFoundCode() {
        assertThatThrownBy(() -> connector().readResource("test://nope"))
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                    assertThat(e.protocolCode()).contains("-32002");
                });
    }

    @Test
    void subscribersHearAboutUpdatesUntilTheyUnsubscribe() throws Exception {
        McpClientConnector c = connector();
        BlockingQueue<List<McpResourceContents>> updates = new LinkedBlockingQueue<>();

        c.subscribe("test://watched-resource", updates::add);
        c.invoke(ToolInvocation.of("touch", Map.of()));

        List<McpResourceContents> update = updates.poll(10, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.get(0).uri()).isEqualTo("test://watched-resource");
        assertThat(update.get(0).text()).startsWith("version ");

        c.unsubscribe("test://watched-resource");
        c.invoke(ToolInvocation.of("touch", Map.of()));
        assertThat(updates.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void listsAndRendersPromptsWithArguments() {
        McpClientConnector c = connector();

        assertThat(c.listPrompts()).extracting(McpPromptInfo::name).contains("test_simple_prompt",
                "test_prompt_with_arguments", "test_prompt_with_embedded_resource", "test_prompt_with_image");
        McpPromptInfo withArgs = c.listPrompts().stream().filter(p -> p.name().equals("test_prompt_with_arguments"))
                .findFirst().orElseThrow();
        assertThat(withArgs.arguments()).containsExactly(McpPromptArgument.required("arg1", "First argument"),
                McpPromptArgument.required("arg2", "Second argument"));

        assertThat(c.getPrompt("test_simple_prompt", Map.of()).messages()).singleElement()
                .satisfies(m -> {
                    assertThat(m.role()).isEqualTo(McpPromptMessage.Role.USER);
                    assertThat(m.text()).isEqualTo("This is a simple prompt for testing.");
                });
        McpPromptResult rendered = c.getPrompt("test_prompt_with_arguments", Map.of("arg1", "a", "arg2", "b"));
        assertThat(rendered.description()).isEqualTo("A prompt with arguments");
        assertThat(rendered.messages().get(0).text()).isEqualTo("Prompt with arguments: arg1='a', arg2='b'");
    }

    @Test
    void missingArgumentsAndUnknownPromptsAreInvalidArguments() {
        McpClientConnector c = connector();

        assertThatThrownBy(() -> c.getPrompt("test_prompt_with_arguments", Map.of("arg1", "a")))
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                    assertThat(e.getMessage()).contains("arg2");
                });
        assertThatThrownBy(() -> c.getPrompt("nope", Map.of()))
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void promptsCarryEmbeddedResourcesAndImages() {
        McpClientConnector c = connector();

        List<McpPromptMessage> embedded = c.getPrompt("test_prompt_with_embedded_resource",
                Map.of("resourceUri", "test://static-text")).messages();
        assertThat(embedded.get(0).content()).containsExactly(
                new Content.Link("test://static-text", "text/plain", null),
                new Content.Text(TestAuthoring.EMBEDDED_TEXT));
        assertThat(embedded.get(1).text()).isEqualTo("Please process the embedded resource above.");

        List<McpPromptMessage> image = c.getPrompt("test_prompt_with_image", Map.of()).messages();
        assertThat(image.get(0).content()).containsExactly(new Content.Data("image/png", TestTools.PNG));
        assertThat(image.get(1).role()).isEqualTo(McpPromptMessage.Role.USER);
    }

    @Test
    void completesPromptArgumentsAndTemplateVariables() {
        McpClientConnector c = connector();

        McpCompletion args = c.completePromptArgument("test_prompt_with_arguments", "arg1", "al", Map.of());
        assertThat(args).isEqualTo(new McpCompletion(List.of("alpha", "alps"), 2, false));
        assertThat(c.completePromptArgument("test_prompt_with_arguments", "arg2", "x", Map.of()).values()).isEmpty();
        assertThat(c.completeTemplateVariable("test://template/{id}/data", "id", "4", Map.of()).values())
                .containsExactly("42");
        assertThat(c.completeTemplateVariable("users://{user}/profile/{age}", "user", "an", Map.of("age", "3"))
                .values()).containsExactly("ann", "anton");
        assertThat(c.completePromptArgument("greet", "name", "A", null).values()).containsExactly("Ada", "Alan");
        McpCompletion many = c.completePromptArgument("numbers", "n", "", Map.of());
        assertThat(many.values()).hasSize(100).startsWith("0", "1");
        assertThat(many.total()).isEqualTo(150);
        assertThat(many.hasMore()).isTrue();
    }

    @Test
    void logMessagesDuringAToolCallHonorTheLevel() throws Exception {
        McpClientConnector c = connector();
        List<McpLogMessage> logs = new CopyOnWriteArrayList<>();
        c.onLog(logs::add);

        c.invoke(ToolInvocation.of("test_tool_with_logging", Map.of()));
        assertThat(await(logs, 2)).extracting(McpLogMessage::data).containsExactly("tool started", "almost done");
        assertThat(logs.get(0).logger()).isEqualTo("test_tool_with_logging");
        assertThat(logs.get(0).level()).isEqualTo(ToolProgress.Level.INFO);

        logs.clear();
        c.setLogLevel(ToolProgress.Level.WARNING);
        c.invoke(ToolInvocation.of("test_tool_with_logging", Map.of()));
        assertThat(await(logs, 1)).extracting(McpLogMessage::data).containsExactly("almost done");

        logs.clear();
        c.setLogLevel(ToolProgress.Level.DEBUG);
        c.invoke(ToolInvocation.of("test_tool_with_logging", Map.of()));
        assertThat(await(logs, 3)).extracting(McpLogMessage::data)
                .containsExactly("debug detail", "tool started", "almost done");
    }

    @Test
    void progressReachesTheCallerOnlyWhenItAsks() {
        McpClientConnector c = connector();
        List<String> seen = new CopyOnWriteArrayList<>();
        ToolProgress recorder = new ToolProgress() {
            @Override
            public void progress(double done, Double total, String message) {
                seen.add(done + "/" + total + " " + message);
            }

            @Override
            public void log(Level level, String message) {
            }
        };

        assertThat(c.invoke(ToolInvocation.of("test_tool_with_progress", Map.of()), null, recorder).text())
                .isEqualTo("progressed");
        assertThat(seen).containsExactly("0.0/100.0 step 0", "50.0/100.0 step 50", "100.0/100.0 step 100");

        seen.clear();
        c.invoke(ToolInvocation.of("test_tool_with_progress", Map.of()));
        assertThat(seen).isEmpty();
    }

    @Test
    void handlersSeeTheCaller() {
        McpClientConnector c = connector();
        String caller = expectedCaller();

        assertThat(c.readResource("test://whoami").get(0).text()).isEqualTo(caller);
        assertThat(c.readResource("notes://today").get(0).text()).isEqualTo("# Note for " + caller);
        McpPromptResult who = c.getPrompt("whoami_prompt", Map.of());
        assertThat(who.description()).isEqualTo("who is asking");
        assertThat(who.messages().get(0).role()).isEqualTo(McpPromptMessage.Role.ASSISTANT);
        assertThat(who.messages().get(0).text()).isEqualTo("You are " + caller);
        assertThat(c.getPrompt("dialogue", Map.of("turns", "2")).messages()).extracting(McpPromptMessage::text)
                .containsExactly(caller + " says 0", "reply 1");
    }

    @Test
    void annotatedResourcesAndPromptsAreServed() {
        McpClientConnector c = connector();

        assertThat(c.listResources()).contains(
                new McpResourceInfo("notes://today", "todayNote", null, "Today's note", "text/markdown"),
                new McpResourceInfo("notes://raw", "raw-bytes", null, null, null));
        McpResourceContents raw = c.readResource("notes://raw").get(0);
        assertThat(raw.mimeType()).isEqualTo("application/octet-stream");
        assertThat(raw.bytes()).containsExactly(1, 2, 3);
        assertThat(c.listResourceTemplates()).contains(
                new McpTemplateInfo("users://{user}/profile/{age}", "profile", "User profile", null, null));

        McpPromptInfo greet = c.listPrompts().stream().filter(p -> p.name().equals("greet")).findFirst().orElseThrow();
        assertThat(greet.description()).isEqualTo("Greets someone");
        assertThat(greet.arguments()).containsExactly(new McpPromptArgument("name", null, "Who to greet", true),
                new McpPromptArgument("punctuation", null, null, false));
        assertThat(c.getPrompt("greet", Map.of("name", "Ada")).messages().get(0).text()).isEqualTo("Hello, Ada!");
        assertThat(c.getPrompt("greet", Map.of("name", "Ada", "punctuation", "?")).messages().get(0).text())
                .isEqualTo("Hello, Ada?");
        assertThat(c.listPrompts()).filteredOn(p -> p.name().equals("dialogue")).extracting(McpPromptInfo::title)
                .containsExactly("A short dialogue");
    }

    /** Waits for {@code count} messages, then briefly for any extra that would break an exact assertion. */
    private static List<McpLogMessage> await(List<McpLogMessage> logs, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (logs.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(200);
        return List.copyOf(logs);
    }
}
