package io.agentweave.spring.boot;

import io.agentweave.core.agent.AgentHandler;
import io.agentweave.springai.AgentWeaveToolCallbacks;
import io.agentweave.springai.CatalogToolIndex;
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

class AgentWeaveSpringAiConfigurationTest {

    private static final ChatModel ECHO_MODEL = prompt -> new ChatResponse(List.of(new Generation(
            new AssistantMessage("echo: " + prompt.getUserMessage().getText()))));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWeaveAutoConfiguration.class,
                    AgentWeaveDiscoveryAutoConfiguration.class, AgentWeaveSpringAiAutoConfiguration.class,
                    AgentWeaveServerAutoConfiguration.class))
            .withBean(TestTools.class);

    @Test
    void nothingIsRegisteredByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AgentWeaveToolCallbacks.class);
            assertThat(context).doesNotHaveBean(ChatClientBuilderCustomizer.class);
            assertThat(context).doesNotHaveBean(CatalogToolIndex.class);
            assertThat(context).doesNotHaveBean(AgentHandler.class);
        });
    }

    @Test
    void toolCallbacksExposeLocalToolsAndCustomizeTheChatClient() {
        runner.withPropertyValues("agentweave.spring-ai.tool-callbacks.enabled=true").run(context -> {
            ToolCallback[] callbacks = context.getBean(AgentWeaveToolCallbacks.class).getToolCallbacks();
            assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                    .containsExactlyInAnyOrder("add", "whoami");
            assertThat(context).hasSingleBean(ChatClientBuilderCustomizer.class);
        });
    }

    @Test
    void exposeRegistryNamespacesTools() {
        runner.withPropertyValues("agentweave.spring-ai.tool-callbacks.enabled=true",
                "agentweave.spring-ai.tool-callbacks.expose=registry").run(context -> {
            ToolCallback[] callbacks = context.getBean(AgentWeaveToolCallbacks.class).getToolCallbacks();
            assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                    .containsExactlyInAnyOrder("local__add", "local__whoami");
        });
    }

    @Test
    void toolSearchReplacesPlainCallbacksOnTheChatClient() {
        runner.withPropertyValues("agentweave.discovery.enabled=true", "agentweave.spring-ai.tool-search.enabled=true",
                "agentweave.spring-ai.tool-callbacks.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CatalogToolIndex.class);
            // Only the tool-search customizer: registering both would duplicate tool names.
            assertThat(context.getBeansOfType(ChatClientBuilderCustomizer.class)).containsOnlyKeys("agentWeaveToolSearchCustomizer");
        });
    }

    @Test
    void chatClientIsServedAsTheA2aAgentWhenAsked() {
        runner.withBean(ChatClient.class, () -> ChatClient.builder(ECHO_MODEL).build())
                .withPropertyValues("agentweave.server.a2a.from-chat-client=true")
                .run(context -> assertThat(context).hasSingleBean(AgentHandler.class));
    }

    @Test
    void anApplicationAgentHandlerWins() {
        AgentHandler mine = (request, task) -> task.reply("mine");
        runner.withBean(ChatClient.class, () -> ChatClient.builder(ECHO_MODEL).build())
                .withBean(AgentHandler.class, () -> mine)
                .withPropertyValues("agentweave.server.a2a.from-chat-client=true")
                .run(context -> assertThat(context.getBean(AgentHandler.class)).isSameAs(mine));
    }
}
