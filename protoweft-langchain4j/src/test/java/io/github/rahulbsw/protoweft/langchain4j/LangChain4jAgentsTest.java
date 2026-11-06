package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentArtifact;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jAgentsTest {

    interface Assistant {
        String chat(@MemoryId String conversationId, @UserMessage String message);
    }

    interface StreamingAssistant {
        TokenStream chat(@MemoryId String conversationId, @UserMessage String message);
    }

    /** Records every call as a short string; the thread of each call is kept too. */
    static final class FakeTask implements AgentTaskContext {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;

        private void record(String event) {
            events.add(event);
            threads.add(Thread.currentThread().getName());
        }

        @Override public String taskId() { return "task-1"; }
        @Override public String contextId() { return "ctx-1"; }
        @Override public void reply(AgentMessage message) { record("reply:" + message.text()); }
        @Override public void working(String status) { record("working:" + status); }
        @Override public void artifact(AgentArtifact artifact) {
            record("artifact:" + artifact.name() + ":" + ((Content.Text) artifact.parts().get(0)).text());
        }
        @Override public void requireInput(String question) { record("input:" + question); }
        @Override public void requireAuth(String reason) { record("auth:" + reason); }
        @Override public void complete(String message) { record("complete"); }
        @Override public void fail(String reason) { record("fail:" + reason); }
        @Override public void reject(String reason) { record("reject:" + reason); }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private static AgentRequest request(String text, String taskId, String contextId) {
        return new AgentRequest(AgentMessage.user(text), taskId, contextId, false, List.of(), null);
    }

    @Test
    void conversationCompletesTaskWithArtifact() throws Exception {
        List<String> ids = new ArrayList<>();
        AgentHandler handler = LangChain4jAgents.handler((id, msg) -> {
            ids.add(id);
            return "hi " + msg;
        });
        FakeTask task = new FakeTask();

        handler.handle(request("bob", "task-1", "ctx-1"), task);
        handler.handle(request("ann", "task-2", null), new FakeTask());

        assertThat(task.events).containsExactly("artifact:response:hi bob", "complete");
        assertThat(ids).containsExactly("ctx-1", "task-2");
    }

    @Test
    void conversationExceptionFailsTask() throws Exception {
        FakeTask task = new FakeTask();

        LangChain4jAgents.handler((id, msg) -> {
            throw new IllegalStateException("model unavailable");
        }).handle(request("x", "t", "c"), task);

        assertThat(task.events).containsExactly("fail:model unavailable");
    }

    @Test
    void aiServiceMethodReferenceServesAsAgent() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(null, null);
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10))
                .build();
        AgentHandler handler = LangChain4jAgents.handler(assistant::chat);

        FakeTask first = new FakeTask();
        handler.handle(request("one", "t1", "conv"), first);
        handler.handle(request("two", "t2", "conv"), new FakeTask());

        assertThat(first.events).containsExactly("artifact:response:echo: one", "complete");
        // Same contextId -> same chat memory: the second request carries the first exchange.
        assertThat(model.requests.get(1).messages()).hasSize(3);
    }

    @Test
    void streamingReportsPartialsThenArtifact() throws Exception {
        StreamingChatModel streaming = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                new Thread(() -> {
                    handler.onPartialResponse("Hel");
                    handler.onPartialResponse("lo");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build());
                }, "model-thread").start();
            }
        };
        StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(streaming)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10))
                .build();
        FakeTask task = new FakeTask();

        LangChain4jAgents.streamingHandler(assistant::chat).handle(request("hi", "t", "c"), task);

        assertThat(task.events).containsExactly("working:Hel", "working:lo", "artifact:response:Hello", "complete");
        assertThat(task.threads).allMatch(name -> name.equals(Thread.currentThread().getName()));
    }

    @Test
    void streamingErrorFailsTask() throws Exception {
        StreamingChatModel streaming = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onError(new RuntimeException("stream broke"));
            }
        };
        StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(streaming)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10))
                .build();
        FakeTask task = new FakeTask();

        LangChain4jAgents.streamingHandler(assistant::chat).handle(request("hi", "t", "c"), task);

        assertThat(task.events).containsExactly("fail:stream broke");
    }

    @Test
    void cancelledStreamingTaskStopsListening() throws Exception {
        FakeTask task = new FakeTask();
        task.cancelled = true;
        StreamingChatModel silent = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            }
        };
        StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(silent)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10))
                .build();

        LangChain4jAgents.streamingHandler(assistant::chat).handle(request("hi", "t", "c"), task);

        assertThat(task.events).isEmpty();
    }

    @Test
    void descriptorHelper() {
        AgentDescriptor card = LangChain4jAgents.descriptor("Assistant", "Answers", "1.0.0",
                LangChain4jAgents.skill("qa", "Q&A", "General questions", "qa", "chat"));

        assertThat(card.name()).isEqualTo("Assistant");
        assertThat(card.skills()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("qa");
            assertThat(s.tags()).containsExactly("qa", "chat");
        });
    }
}
