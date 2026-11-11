package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.springai.ProtoweftToolCallbacks;
import io.github.rahulbsw.protoweft.springai.CatalogToolIndex;
import io.github.rahulbsw.protoweft.springai.SpringAiAgents;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI integration: Protoweft tools as ToolCallbacks, the capability catalog as Spring
 * AI's tool-search index, and a ChatClient served as an A2A agent. Every part is opt-in.
 * Plain callbacks and tool search are mutually exclusive on the ChatClient.Builder, because
 * both register the same tool names and Spring AI rejects duplicates.
 */
// No ordering against the server auto-configuration: it resolves the AgentHandler lazily.
@AutoConfiguration(after = {ProtoweftAutoConfiguration.class, ProtoweftDiscoveryAutoConfiguration.class})
@ConditionalOnClass(name = "io.github.rahulbsw.protoweft.springai.ProtoweftToolCallbacks")
@EnableConfigurationProperties({ProtoweftSpringAiProperties.class, ProtoweftServerProperties.class})
public class ProtoweftSpringAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "protoweft.spring-ai.tool-callbacks", name = "enabled", havingValue = "true")
    ProtoweftToolCallbacks protoweftToolCallbacks(ProtoweftSpringAiProperties properties,
                                                    ObjectProvider<LocalTools> localTools, ToolRegistry registry) {
        ToolSource source = switch (properties.getToolCallbacks().getExpose()) {
            case LOCAL -> localTools.getIfAvailable(() -> LocalTools.builder().build());
            case REGISTRY -> registry;
        };
        return ProtoweftToolCallbacks.from(source);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClientBuilderCustomizer")
    @ConditionalOnBean(ProtoweftToolCallbacks.class)
    @ConditionalOnProperty(prefix = "protoweft.spring-ai.tool-search", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    ChatClientBuilderCustomizer protoweftToolCallbacksCustomizer(ProtoweftToolCallbacks callbacks) {
        return builder -> builder.defaultTools(callbacks);
    }

    /** Spring AI's own tool-search advisor, searching the Protoweft catalog. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.ai.tool.toolsearch.ToolIndex",
            "org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor",
            "io.github.rahulbsw.protoweft.discovery.CapabilityCatalog"})
    @ConditionalOnProperty(prefix = "protoweft.spring-ai.tool-search", name = "enabled", havingValue = "true")
    static class ToolSearch {

        @Bean
        @ConditionalOnMissingBean
        CatalogToolIndex protoweftCatalogToolIndex(CapabilityCatalog catalog) {
            return CatalogToolIndex.from(catalog);
        }

        @Bean
        ChatClientBuilderCustomizer protoweftToolSearchCustomizer(CatalogToolIndex index) {
            return builder -> builder
                    .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).build())
                    .defaultTools(index.toolCallbacks());
        }
    }

    /** {@code protoweft.server.a2a.from-chat-client=true}: serve the ChatClient as the A2A agent. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.ai.chat.client.ChatClient", "io.github.rahulbsw.protoweft.a2a.A2aAgentServer"})
    @ConditionalOnProperty(prefix = "protoweft.server.a2a", name = "from-chat-client", havingValue = "true")
    static class ChatClientAgent {

        @Bean
        @ConditionalOnMissingBean(AgentHandler.class)
        AgentHandler protoweftChatClientAgent(ObjectProvider<ChatClient> chatClient,
                                               ObjectProvider<ChatClient.Builder> builder,
                                               ProtoweftServerProperties properties) {
            ChatClient client = chatClient.getIfUnique(() -> builder.getObject().build());
            return properties.getA2a().isStreaming()
                    ? SpringAiAgents.streamingHandler(client) : SpringAiAgents.handler(client);
        }
    }

    /**
     * {@code protoweft.server.agui.from-chat-client=true}: serve the ChatClient as the AG-UI agent
     * (streaming). The prompt is the conversation each run carries, so the ChatClient should not
     * also have a chat-memory advisor.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.ai.chat.client.ChatClient",
            "io.github.rahulbsw.protoweft.agui.AgUiAgentServer"})
    @ConditionalOnProperty(prefix = "protoweft.server.agui", name = "from-chat-client", havingValue = "true")
    static class ChatClientAgUiAgent {

        @Bean
        @ConditionalOnMissingBean(AgentHandler.class)
        AgentHandler protoweftChatClientAgUiAgent(ObjectProvider<ChatClient> chatClient,
                                                  ObjectProvider<ChatClient.Builder> builder) {
            return SpringAiAgents.streamingHandler(chatClient.getIfUnique(() -> builder.getObject().build()));
        }
    }
}
