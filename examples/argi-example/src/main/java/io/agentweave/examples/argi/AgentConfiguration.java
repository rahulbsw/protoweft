package io.agentweave.examples.argi;

import io.agentweave.argi.ArgiAgents;
import io.agentweave.argi.CatalogToolSelectionInterceptor;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.discovery.CapabilityCatalog;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The explicit wiring between ARGI and AgentWeave:
 * <ul>
 *   <li>the {@link ReactAgent} gets its tools from the starter's {@link CapabilityCatalog} (the
 *       registry's {@code @AgentTool} methods plus any configured MCP/UTCP/A2A sources): the
 *       {@link CatalogToolSelectionInterceptor} registers every capability and offers the model,
 *       on each call, only those the catalog finds for the request and the caller may see;</li>
 *   <li>{@link HumanInTheLoopHook} pauses before {@code local__transfer}, and a
 *       {@link MemorySaver} keeps the paused run;</li>
 *   <li>the {@link AgentHandler} bean, which the starter serves over A2A, runs the agent per task
 *       and turns the pause into {@code input-required}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

    @Bean
    public ReactAgent bankAgent(ChatModel chatModel, CapabilityCatalog catalog,
                                @Value("${spring.ai.openai.api-key:}") String apiKey) {
        if (apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY is not set: the application runs, but model calls will be rejected");
        }
        return ReactAgent.builder()
                .name("bank-assistant")
                .model(chatModel)
                .systemPrompt("You are a concise banking assistant. Use the tools for balances, rates and transfers.")
                .interceptors(CatalogToolSelectionInterceptor.builder(catalog).limit(3).build())
                .hooks(HumanInTheLoopHook.builder().approvalOn("local__transfer", "Moves real money").build())
                .saver(new MemorySaver())
                // A model failure fails the A2A task instead of becoming an "Exception: ..." answer.
                .throwOnModelError(true)
                .build();
    }

    @Bean
    public AgentHandler bankAgentHandler(ReactAgent bankAgent) {
        return ArgiAgents.handler(bankAgent);
    }

    @Bean
    public AgentDescriptor bankAgentCard() {
        return new AgentDescriptor("argi-bank-assistant", "An ARGI agent using AgentWeave tools", "1.0.0",
                List.of(new AgentDescriptor.Skill("banking", "Banking",
                        "Balances, exchange rates and approved transfers", List.of("banking"), List.of())));
    }
}
