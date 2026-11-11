package io.github.rahulbsw.protoweft.examples.agui;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.springai.ProtoweftToolCallbacks;
import io.github.rahulbsw.protoweft.springai.SpringAiAgents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent the starter serves over AG-UI ({@code protoweft.server.agui.enabled}): a ChatClient
 * with the application's Protoweft tools. It has no chat-memory advisor on purpose: AG-UI clients
 * send the whole conversation with every run, and the handler uses that. The page's own tools
 * (here {@code set_theme}) arrive with each run and are offered to the model automatically.
 */
@Configuration(proxyBeanMethods = false)
public class WebAgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAgentConfiguration.class);

    @Bean
    public ChatClient webChatClient(ChatClient.Builder builder, ToolRegistry registry,
                                    @Value("${spring.ai.openai.api-key:}") String apiKey) {
        if (apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY is not set: the application runs, but model calls will be rejected");
        }
        return builder
                .defaultSystem("You are a concise assistant in a web page. Use local__temperature for "
                        + "temperatures. When the user asks to change the page's colour, call set_theme.")
                .defaultTools(ProtoweftToolCallbacks.from(registry))
                .build();
    }

    @Bean
    public AgentHandler webAgent(ChatClient webChatClient) {
        return SpringAiAgents.streamingHandler(webChatClient);
    }
}
