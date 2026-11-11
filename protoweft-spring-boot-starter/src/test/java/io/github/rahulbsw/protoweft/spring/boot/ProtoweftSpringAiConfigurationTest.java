package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.springai.ProtoweftToolCallbacks;
import io.github.rahulbsw.protoweft.springai.CatalogToolIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoweftSpringAiConfigurationTest {

    private static final ChatModel ECHO_MODEL = prompt -> new ChatResponse(List.of(new Generation(
            new AssistantMessage("echo: " + prompt.getUserMessage().getText()))));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtoweftAutoConfiguration.class,
                    ProtoweftDiscoveryAutoConfiguration.class, ProtoweftSpringAiAutoConfiguration.class,
                    ProtoweftServerAutoConfiguration.class))
            .withBean(TestTools.class);

    @Test
    void nothingIsRegisteredByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ProtoweftToolCallbacks.class);
            assertThat(context).doesNotHaveBean(ChatClientBuilderCustomizer.class);
            assertThat(context).doesNotHaveBean(CatalogToolIndex.class);
            assertThat(context).doesNotHaveBean(AgentHandler.class);
        });
    }

    @Test
    void toolCallbacksExposeLocalToolsAndCustomizeTheChatClient() {
        runner.withPropertyValues("protoweft.spring-ai.tool-callbacks.enabled=true").run(context -> {
            ToolCallback[] callbacks = context.getBean(ProtoweftToolCallbacks.class).getToolCallbacks();
            assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                    .containsExactlyInAnyOrder("add", "whoami");
            assertThat(context).hasSingleBean(ChatClientBuilderCustomizer.class);
        });
    }

    @Test
    void exposeRegistryNamespacesTools() {
        runner.withPropertyValues("protoweft.spring-ai.tool-callbacks.enabled=true",
                "protoweft.spring-ai.tool-callbacks.expose=registry").run(context -> {
            ToolCallback[] callbacks = context.getBean(ProtoweftToolCallbacks.class).getToolCallbacks();
            assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                    .containsExactlyInAnyOrder("local__add", "local__whoami");
        });
    }

    @Test
    void toolSearchReplacesPlainCallbacksOnTheChatClient() {
        runner.withPropertyValues("protoweft.discovery.enabled=true", "protoweft.spring-ai.tool-search.enabled=true",
                "protoweft.spring-ai.tool-callbacks.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CatalogToolIndex.class);
            // Only the tool-search customizer: registering both would duplicate tool names.
            assertThat(context.getBeansOfType(ChatClientBuilderCustomizer.class)).containsOnlyKeys("protoweftToolSearchCustomizer");
        });
    }

    @Test
    void chatClientIsServedAsTheA2aAgentWhenAsked() {
        runner.withBean(ChatClient.class, () -> ChatClient.builder(ECHO_MODEL).build())
                .withPropertyValues("protoweft.server.a2a.from-chat-client=true")
                .run(context -> assertThat(context).hasSingleBean(AgentHandler.class));
    }

    @Test
    void chatClientIsServedAsTheAgUiAgentWhenAsked() {
        runner.withBean(ChatClient.class, () -> ChatClient.builder(ECHO_MODEL).build())
                .withPropertyValues("protoweft.server.agui.from-chat-client=true")
                .run(context -> assertThat(context).hasSingleBean(AgentHandler.class));
        runner.withBean(ChatClient.class, () -> ChatClient.builder(ECHO_MODEL).build())
                .withPropertyValues("protoweft.server.agui.from-chat-client=true", "protoweft.server.a2a.from-chat-client=true")
                .run(context -> assertThat(context).hasSingleBean(AgentHandler.class));
    }

    @Test
    void anApplicationAgentHandlerWins() {
        AgentHandler mine = (request, task) -> task.reply("mine");
        runner.withBean(ChatClient.class, () -> ChatClient.builder(ECHO_MODEL).build())
                .withBean(AgentHandler.class, () -> mine)
                .withPropertyValues("protoweft.server.a2a.from-chat-client=true")
                .run(context -> assertThat(context.getBean(AgentHandler.class)).isSameAs(mine));
    }
}
