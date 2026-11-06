package io.github.rahulbsw.protoweft.examples.springai;

import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.springai.ProtoweftToolCallbacks;
import io.github.rahulbsw.protoweft.springai.SpringAiAgents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The explicit wiring between Spring AI and Protoweft:
 * <ul>
 *   <li>the {@link ChatClient} gets every tool of the Protoweft {@link ToolRegistry} (the
 *       starter's bean: local {@code @AgentTool} methods plus configured MCP/UTCP/A2A sources)
 *       through {@link ProtoweftToolCallbacks}, and remembers conversations per A2A context;</li>
 *   <li>the {@link AgentHandler} bean, which the starter serves over A2A, runs that client and
 *       streams its answer.</li>
 * </ul>
 * The tool callbacks are deliberately not a bean: Spring AI's MCP server, if added, would
 * re-export every {@code ToolCallbackProvider} bean.
 */
@Configuration(proxyBeanMethods = false)
public class AssistantConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfiguration.class);

    @Bean
    public ChatClient assistantChatClient(ChatClient.Builder builder, ChatMemory chatMemory, ToolRegistry registry,
                                          @Value("${spring.ai.openai.api-key:}") String apiKey) {
        if (apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY is not set: the application runs, but model calls will be rejected");
        }
        return builder
                .defaultSystem("You are a concise assistant. Use the tools for temperatures, times and identity.")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(ProtoweftToolCallbacks.from(registry))
                .build();
    }

    @Bean
    public AgentHandler assistantAgent(ChatClient assistantChatClient) {
        return SpringAiAgents.streamingHandler(assistantChatClient);
    }

    @Bean
    public AgentDescriptor assistantCard() {
        return SpringAiAgents.descriptor("spring-ai-assistant", "A Spring AI assistant using Protoweft tools",
                "1.0.0", SpringAiAgents.skill("assistant", "Assistant",
                        "Answers questions about temperatures and times", "weather", "time"));
    }
}
