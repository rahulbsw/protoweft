package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import io.github.rahulbsw.protoweft.agui.AgUi;
import io.github.rahulbsw.protoweft.agui.AgUiAgentServer;
import io.github.rahulbsw.protoweft.agui.AgUiClient;
import io.github.rahulbsw.protoweft.agui.AgUiEvent;
import io.github.rahulbsw.protoweft.agui.AgUiMessage;
import io.github.rahulbsw.protoweft.agui.AgUiThread;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A LangChain4j AI Service served over AG-UI. */
class LangChain4jAgUiTest {

    interface Assistant {
        TokenStream chat(@MemoryId String conversationId, @dev.langchain4j.service.UserMessage String message);
    }

    static final class Tools {
        @Tool("Looks the answer up")
        String lookup() {
            return "42";
        }
    }

    /** Calls the tool once if asked to look something up, then answers; records every request. */
    static final class Model implements StreamingChatModel {
        final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<>();

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request.messages());
            ChatMessage last = request.messages().get(request.messages().size() - 1);
            String user = request.messages().stream().filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                    .map(m -> ((dev.langchain4j.data.message.UserMessage) m).singleText()).reduce((a, b) -> b).orElse("");
            if (user.startsWith("look") && !(last instanceof ToolExecutionResultMessage)) {
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{}").build())).build());
                return;
            }
            String answer = last instanceof ToolExecutionResultMessage r ? "found " + r.text() : "heard " + user;
            handler.onPartialResponse(answer);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(answer)).build());
        }
    }

    private ProtoweftServer host;
    private AgUiAgentServer server;
    private final Model model = new Model();
    private final MessageWindowChatMemory stored = MessageWindowChatMemory.withMaxMessages(50);

    private AgUiClient serve(boolean frontendHistory) {
        AiServices<Assistant> builder = AiServices.builder(Assistant.class).streamingChatModel(model).tools(new Tools());
        builder = frontendHistory ? builder.chatMemoryProvider(LangChain4jAgents.chatMemory(id -> stored))
                : builder.chatMemoryProvider(id -> stored);
        Assistant assistant = builder.build();
        server = AgUiAgentServer.builder(AgUi.agent(LangChain4jAgents.streamingHandler(assistant::chat))).build();
        host = ProtoweftServer.builder().port(0).endpoints(server).start();
        return AgUiClient.builder(host.baseUrl() + "/agui").build();
    }

    @AfterEach
    void stop() {
        host.close();
        server.close();
    }

    @Test
    void theAiServiceSeesTheFrontendsConversationAndItsMemoryIsUntouched() {
        AgUiThread thread = AgUiThread.builder(serve(true))
                .messages(List.of(AgUiMessage.UserMessage.text("u1", "earlier question"),
                        AgUiMessage.AssistantMessage.text("a1", "earlier answer")))
                .build();

        assertThat(thread.send("now?").text()).isEqualTo("heard now?");

        List<ChatMessage> sent = model.requests.get(0);
        assertThat(sent).hasSize(3);
        assertThat(((dev.langchain4j.data.message.UserMessage) sent.get(0)).singleText()).isEqualTo("earlier question");
        assertThat(((AiMessage) sent.get(1)).text()).isEqualTo("earlier answer");
        assertThat(stored.messages()).isEmpty();
    }

    @Test
    void toolCallsAreShownInTheStream() {
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        AgUiThread.Turn turn = AgUiThread.builder(serve(true)).listener(events::add).build().send("look it up");

        assertThat(turn.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(turn.text()).isEqualTo("found 42");
        assertThat(events).filteredOn(AgUiEvent.ToolCallStart.class::isInstance)
                .extracting(e -> ((AgUiEvent.ToolCallStart) e).toolCallName()).containsExactly("lookup");
        assertThat(events).filteredOn(AgUiEvent.ToolCallResult.class::isInstance)
                .extracting(e -> ((AgUiEvent.ToolCallResult) e).content()).containsExactly("42");
        assertThat(turn.lastRun().pendingToolCalls()).isEmpty();
    }

    @Test
    void withoutTheProviderTheServiceKeepsUsingItsOwnMemory() {
        AgUiThread thread = AgUiThread.builder(serve(false))
                .messages(List.of(AgUiMessage.UserMessage.text("u1", "earlier question"))).build();
        thread.send("now?");
        assertThat(model.requests.get(0)).hasSize(1);   // the frontend's history was not used
        assertThat(stored.messages()).isNotEmpty();
    }
}
