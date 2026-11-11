package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.agent.AgentArtifact;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiAgentsTest {

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
        @Override public void textDelta(String delta) { record("delta:" + delta); }
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

    private static ChatClient withMemory(ChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
                .build();
    }

    @Test
    void blockingHandlerCompletesWithArtifactAndMemoryFollowsContext() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.answer("hi bob"),
                ScriptedChatModel.answer("again"), ScriptedChatModel.answer("other"));
        AgentHandler handler = SpringAiAgents.handler(withMemory(model));
        FakeTask task = new FakeTask();

        handler.handle(request("bob", "t1", "conv"), task);
        handler.handle(request("more", "t2", "conv"), new FakeTask());
        handler.handle(request("new", "t3", null), new FakeTask());

        assertThat(task.events).containsExactly("artifact:response:hi bob", "complete");
        // Same contextId -> same memory: user, assistant, user.
        assertThat(model.prompts.get(1).getInstructions()).hasSize(3);
        // No contextId -> the taskId is a fresh conversation.
        assertThat(model.prompts.get(2).getInstructions()).hasSize(1);
    }

    @Test
    void callerReachesProtoweftTools() throws Exception {
        List<String> callers = new CopyOnWriteArrayList<>();
        LocalTools tools = LocalTools.builder()
                .tool("whoami", "Says who is calling", Map.of("type", "object"), (args, caller) -> {
                    callers.add(caller.name());
                    return ToolOutcome.text(caller.name());
                })
                .build();
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.callTool("whoami", "{}"),
                ScriptedChatModel.echoLastToolResult());
        ChatClient chat = ChatClient.builder(model).defaultTools(ProtoweftToolCallbacks.from(tools)).build();
        FakeTask task = new FakeTask();

        SpringAiAgents.handler(chat).handle(new AgentRequest(AgentMessage.user("who am I?"), "t", "c", false,
                List.of(), CallerIdentity.of("alice")), task);

        assertThat(callers).containsExactly("alice");
        assertThat(task.events).containsExactly("artifact:response:result: alice", "complete");
    }

    @Test
    void customizerAdjustsRequest() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.answer("ok"));
        SpringAiAgents.handler(ChatClient.create(model), (request, spec) -> spec.system("be brief"))
                .handle(request("x", "t", "c"), new FakeTask());

        assertThat(model.prompts.get(0).getSystemMessage().getText()).isEqualTo("be brief");
    }

    @Test
    void blockingFailureFailsTask() throws Exception {
        ChatModel broken = prompt -> {
            throw new IllegalStateException("model unavailable");
        };
        FakeTask task = new FakeTask();
        FakeTask blank = new FakeTask();

        SpringAiAgents.handler(ChatClient.create(broken)).handle(request("x", "t", "c"), task);
        SpringAiAgents.handler(ChatClient.create(broken)).handle(request(" ", "t", "c"), blank);

        assertThat(task.events).containsExactly("fail:model unavailable");
        assertThat(blank.events).containsExactly("fail:the message has no text");
    }

    @Test
    void streamingReportsChunksAsTextDeltasOnHandlerThread() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.answer("Hello big world"));
        FakeTask task = new FakeTask();

        SpringAiAgents.streamingHandler(withMemory(model)).handle(request("hi", "t", "c"), task);

        assertThat(task.events).containsExactly("delta:Hello ", "delta:big ", "delta:world", "complete");
        assertThat(task.threads).allMatch(name -> name.equals(Thread.currentThread().getName()));
    }

    @Test
    void streamingRunsProtoweftToolsAsTheCaller() throws Exception {
        List<String> callers = new CopyOnWriteArrayList<>();
        LocalTools tools = LocalTools.builder()
                .tool("whoami", "Says who is calling", Map.of("type", "object"), (args, caller) -> {
                    callers.add(caller.name());
                    return ToolOutcome.text(caller.name());
                })
                .build();
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.callTool("whoami", "{}"),
                ScriptedChatModel.echoLastToolResult());
        ChatClient chat = ChatClient.builder(model).defaultTools(ProtoweftToolCallbacks.from(tools)).build();
        FakeTask task = new FakeTask();

        SpringAiAgents.streamingHandler(chat).handle(new AgentRequest(AgentMessage.user("who am I?"), "t", "c",
                false, List.of(), CallerIdentity.of("bob")), task);

        assertThat(callers).containsExactly("bob");
        assertThat(task.events).containsExactly("delta:result: ", "delta:bob", "complete");
    }

    @Test
    void streamingErrorFailsTask() throws Exception {
        FakeTask task = new FakeTask();

        SpringAiAgents.streamingHandler(ChatClient.create(streaming(Flux.error(new RuntimeException("stream broke")))))
                .handle(request("hi", "t", "c"), task);

        assertThat(task.events).containsExactly("fail:stream broke");
    }

    @Test
    void cancelledStreamingTaskDisposesTheStream() throws Exception {
        AtomicBoolean cancelledUpstream = new AtomicBoolean();
        FakeTask task = new FakeTask();
        task.cancelled = true;

        SpringAiAgents.streamingHandler(ChatClient.create(streaming(
                        Flux.<ChatResponse>never().doOnCancel(() -> cancelledUpstream.set(true)))))
                .handle(request("hi", "t", "c"), task);

        assertThat(task.events).isEmpty();
        assertThat(cancelledUpstream).isTrue();
    }

    @Test
    void descriptorHelper() {
        AgentDescriptor card = SpringAiAgents.descriptor("Assistant", "Answers", "1.0.0",
                SpringAiAgents.skill("qa", "Q&A", "General questions", "qa", "chat"));

        assertThat(card.name()).isEqualTo("Assistant");
        assertThat(card.skills()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("qa");
            assertThat(s.tags()).containsExactly("qa", "chat");
        });
    }

    private static ChatModel streaming(Flux<ChatResponse> responses) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return responses;
            }
        };
    }
}
