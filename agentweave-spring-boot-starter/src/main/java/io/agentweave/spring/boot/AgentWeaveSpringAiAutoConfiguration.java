package io.agentweave.spring.boot;

import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.springai.AgentWeaveToolCallbacks;
import io.agentweave.springai.CatalogToolIndex;
import io.agentweave.springai.SpringAiAgents;
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
 * Spring AI integration: AgentWeave tools as ToolCallbacks, the capability catalog as Spring
 * AI's tool-search index, and a ChatClient served as an A2A agent. Every part is opt-in.
 * Plain callbacks and tool search are mutually exclusive on the ChatClient.Builder, because
 * both register the same tool names and Spring AI rejects duplicates.
 */
// No ordering against the server auto-configuration: it resolves the AgentHandler lazily.
@AutoConfiguration(after = {AgentWeaveAutoConfiguration.class, AgentWeaveDiscoveryAutoConfiguration.class})
@ConditionalOnClass(name = "io.agentweave.springai.AgentWeaveToolCallbacks")
@EnableConfigurationProperties({AgentWeaveSpringAiProperties.class, AgentWeaveServerProperties.class})
public class AgentWeaveSpringAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentweave.spring-ai.tool-callbacks", name = "enabled", havingValue = "true")
    AgentWeaveToolCallbacks agentWeaveToolCallbacks(AgentWeaveSpringAiProperties properties,
                                                    ObjectProvider<LocalTools> localTools, ToolRegistry registry) {
        ToolSource source = switch (properties.getToolCallbacks().getExpose()) {
            case LOCAL -> localTools.getIfAvailable(() -> LocalTools.builder().build());
            case REGISTRY -> registry;
        };
        return AgentWeaveToolCallbacks.from(source);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClientBuilderCustomizer")
    @ConditionalOnBean(AgentWeaveToolCallbacks.class)
    @ConditionalOnProperty(prefix = "agentweave.spring-ai.tool-search", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    ChatClientBuilderCustomizer agentWeaveToolCallbacksCustomizer(AgentWeaveToolCallbacks callbacks) {
        return builder -> builder.defaultTools(callbacks);
    }

    /** Spring AI's own tool-search advisor, searching the AgentWeave catalog. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.ai.tool.toolsearch.ToolIndex",
            "org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor",
            "io.agentweave.discovery.CapabilityCatalog"})
    @ConditionalOnProperty(prefix = "agentweave.spring-ai.tool-search", name = "enabled", havingValue = "true")
    static class ToolSearch {

        @Bean
        @ConditionalOnMissingBean
        CatalogToolIndex agentWeaveCatalogToolIndex(CapabilityCatalog catalog) {
            return CatalogToolIndex.from(catalog);
        }

        @Bean
        ChatClientBuilderCustomizer agentWeaveToolSearchCustomizer(CatalogToolIndex index) {
            return builder -> builder
                    .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).build())
                    .defaultTools(index.toolCallbacks());
        }
    }

    /** {@code agentweave.server.a2a.from-chat-client=true}: serve the ChatClient as the A2A agent. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.ai.chat.client.ChatClient", "io.agentweave.a2a.A2aAgentServer"})
    @ConditionalOnProperty(prefix = "agentweave.server.a2a", name = "from-chat-client", havingValue = "true")
    static class ChatClientAgent {

        @Bean
        @ConditionalOnMissingBean(AgentHandler.class)
        AgentHandler agentWeaveChatClientAgent(ObjectProvider<ChatClient> chatClient,
                                               ObjectProvider<ChatClient.Builder> builder,
                                               AgentWeaveServerProperties properties) {
            ChatClient client = chatClient.getIfUnique(() -> builder.getObject().build());
            return properties.getA2a().isStreaming()
                    ? SpringAiAgents.streamingHandler(client) : SpringAiAgents.handler(client);
        }
    }
}
