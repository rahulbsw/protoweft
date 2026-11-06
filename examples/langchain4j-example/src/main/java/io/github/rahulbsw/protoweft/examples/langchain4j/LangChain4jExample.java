package io.github.rahulbsw.protoweft.examples.langchain4j;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.langchain4j.ProtoweftToolProvider;
import io.github.rahulbsw.protoweft.langchain4j.LangChain4jAgents;
import io.github.rahulbsw.protoweft.langchain4j.ToolNames;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * A LangChain4j AI service that uses Protoweft tools, served to other agents over A2A.
 *
 * <p>Set {@code OPENAI_API_KEY} and {@code OPENAI_MODEL} (and optionally
 * {@code OPENAI_BASE_URL} for any OpenAI-compatible server). Without them the example shows
 * the tools LangChain4j would receive and stops before calling a model.
 */
public final class LangChain4jExample {

    /** Protoweft tools; the same objects could be served over MCP or UTCP. */
    public static class Orders {
        @AgentTool(description = "Status of an order by id", readOnly = true, tags = {"orders"})
        public String orderStatus(@ToolParam(description = "Order id, e.g. A-1001") String orderId) {
            return orderId.equals("A-1001") ? "shipped on 2026-11-03" : "unknown order " + orderId;
        }
    }

    /** The LangChain4j AI service; its method fits {@link LangChain4jAgents.Conversation}. */
    interface SupportAgent {
        String chat(@MemoryId String conversationId, @UserMessage String message);
    }

    public static void main(String[] args) throws IOException {
        ToolRegistry registry = new ToolRegistry().register("shop", LocalTools.builder().scan(new Orders()).build());
        ProtoweftToolProvider tools = ProtoweftToolProvider.from(registry);

        System.out.println("Tools LangChain4j receives from Protoweft:");
        registry.listTools().forEach(spec -> System.out.println(
                "  - " + ToolNames.sanitize(spec.name()) + " (Protoweft: " + spec.name() + "): " + spec.description()));

        String apiKey = System.getenv("OPENAI_API_KEY");
        String modelName = System.getenv("OPENAI_MODEL");
        if (apiKey == null || apiKey.isBlank() || modelName == null || modelName.isBlank()) {
            System.out.println("\nSet OPENAI_API_KEY and OPENAI_MODEL (and optionally OPENAI_BASE_URL) to run the agent.");
            return;
        }

        OpenAiChatModel.OpenAiChatModelBuilder model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName);
        if (System.getenv("OPENAI_BASE_URL") != null) {
            model.baseUrl(System.getenv("OPENAI_BASE_URL"));
        }
        SupportAgent agent = AiServices.builder(SupportAgent.class)
                .chatModel(model.build())
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .toolProvider(tools)
                .build();

        // Serve the AI service as an A2A agent, then call it the way another agent would.
        String base = "http://127.0.0.1:" + freePort();
        try (A2aAgentServer a2a = A2aAgentServer.builder(LangChain4jAgents.handler(agent::chat),
                        LangChain4jAgents.descriptor("Support agent", "Answers order questions", "1.0.0",
                                LangChain4jAgents.skill("orders", "Order status", "Looks up order status", "orders")))
                .publicUrl(base).build();
             ProtoweftServer server = ProtoweftServer.builder().host("127.0.0.1")
                     .port(Integer.parseInt(base.substring(base.lastIndexOf(':') + 1))).endpoints(a2a).start();
             A2aAgentConnector client = A2aAgentConnector.builder(base).build()) {
            client.start();
            System.out.println("\nA2A agent at " + base + " — asking it about order A-1001...");
            System.out.println("Reply: " + client.send(AgentMessage.user("Where is my order A-1001?")).text());
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
