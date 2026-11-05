package io.agentweave.argi.neutral;

import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentRequest;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ArgiNeutralAgentsTest {

    private static final CallerIdentity BOB = CallerIdentity.of("bob");

    private static AgentRequest request(String text, String taskId, boolean resumed, List<AgentMessage> history) {
        return new AgentRequest(AgentMessage.user(text), taskId, "ctx-1", resumed, history, BOB);
    }

    private static AgentOutcome completed(List<ChatMessage> messages, Map<String, Object> outputs) {
        return AgentOutcome.completed(messages, outputs, Usage.unknown());
    }

    private static AgentOutcome suspended(String token, Map<String, Object> prompts, List<ChatMessage> messages) {
        return new AgentOutcome(AgentOutcome.Status.SUSPENDED, messages, Map.of("step", 1), Usage.unknown(),
                new AgentOutcome.Suspension(token, prompts));
    }

    /** Streams deltas and suspends once; resumed with the token, it answers using the reply. */
    private static ScriptedAgent approvalAgent() {
        return new ScriptedAgent(req -> {
            if (req.resumeToken() == null) {
                ChatMessage ask = ChatMessage.assistant(null, List.of(new ToolCall("c1", "delete_all", "{}")));
                return List.of(new AgentEvent.MessageDelta("m1", "Let me "), new AgentEvent.MessageDelta("m1", "check"),
                        new AgentEvent.Progress("model.tool-call", Map.of("name", "delete_all")),
                        new AgentEvent.Message("m1", ask),
                        new AgentEvent.UsageUpdate(Usage.unknown()),
                        new AgentEvent.Finished(suspended("tok-1", Map.of("approval", "Approve delete_all?"),
                                List.of(ChatMessage.user("clean up"), ask))));
            }
            ChatMessage done = ChatMessage.assistant("done (" + req.inputs().get(ArgiNeutralAgents.REPLY_KEY) + ")");
            List<ChatMessage> all = new ArrayList<>(req.messages());
            all.add(done);
            return List.of(new AgentEvent.MessageDelta("m2", "done"), new AgentEvent.Message("m2", done),
                    new AgentEvent.Finished(completed(all, Map.of())));
        });
    }

    @Test
    void streamsSuspendsAndResumesOnTheSameTask() throws Exception {
        ScriptedAgent agent = approvalAgent();
        AgentHandler handler = ArgiNeutralAgents.handler(agent);
        FakeTask first = new FakeTask("task-1");
        FakeTask second = new FakeTask("task-1");

        handler.handle(request("clean up", "task-1", false, List.of()), first);
        handler.handle(new AgentRequest(new AgentMessage(AgentMessage.Role.USER,
                List.of(new Content.Text("yes"), new Content.Json(Map.of("decision", "approve"))), null, "task-1",
                "ctx-1", null), "task-1", "ctx-1", true, List.of(AgentMessage.user("clean up")), BOB), second);

        assertThat(first.events).containsExactly("working:Let me ", "working:check",
                "working:model.tool-call {name=delete_all}", "working:calling delete_all",
                "input:Approve delete_all?");
        assertThat(second.events).containsExactly("working:done", "artifact:response:done (yes)", "complete");
        // Every task call is made on the handler's thread.
        assertThat(first.threads).containsOnly(Thread.currentThread().getName());

        io.github.agentic.ai.agent.AgentRequest fresh = agent.requests.get(0);
        assertThat(fresh.resumeToken()).isNull();
        assertThat(fresh.messages()).extracting(ChatMessage::text).containsExactly("clean up");
        assertThat(fresh.inputs()).containsEntry(ArgiNeutralTools.CALLER_KEY, BOB)
                .containsEntry(ArgiNeutralAgents.TASK_ID_KEY, "task-1")
                .containsEntry(ArgiNeutralAgents.CONTEXT_ID_KEY, "ctx-1");
        io.github.agentic.ai.agent.AgentRequest resumed = agent.requests.get(1);
        assertThat(resumed.resumeToken()).isEqualTo("tok-1");
        assertThat(resumed.messages()).hasSize(2);
        assertThat(resumed.messages().get(1).hasToolCalls()).isTrue();
        assertThat(resumed.inputs()).containsEntry("step", 1)
                .containsEntry(ArgiNeutralAgents.REPLY_KEY, "yes")
                .containsEntry(ArgiNeutralAgents.REPLY_DATA_KEY, Map.of("decision", "approve"))
                .containsEntry(ArgiNeutralTools.CALLER_KEY, BOB);
        // The default trusted context carries the task and caller.
        assertThat(agent.contexts.get(0).trusted().runId()).isEqualTo("task-1");
        assertThat(agent.contexts.get(0).trusted().correlationId()).isEqualTo("ctx-1");
        assertThat(agent.contexts.get(0).trusted().principal().id()).isEqualTo("bob");
    }

    @Test
    void suspensionsAreKeptInTheConfiguredStoreAndPromptsAreJoined() throws Exception {
        Map<String, ArgiNeutralAgents.SuspendedRun> store = new ConcurrentHashMap<>();
        ScriptedAgent agent = new ScriptedAgent(req -> List.of(new AgentEvent.Finished(
                suspended("tok-9", Map.of("b", "Second?", "a", "First?"), List.of()))));
        FakeTask task = new FakeTask("task-9");

        ArgiNeutralAgents.builder(agent).suspensions(store).build()
                .handle(request("go", "task-9", false, List.of()), task);

        assertThat(task.events).containsExactly("input:a: First?\nb: Second?");
        assertThat(store.get("task-9").resumeToken()).isEqualTo("tok-9");
        assertThat(NeutralAgentHandler.question(Map.of())).isEqualTo("Input required");
    }

    @Test
    void historyIsReplayedForAFreshRunAndStructuredOutputIsAddedAsJson() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(req -> List.of(new AgentEvent.Finished(completed(
                List.of(ChatMessage.assistant("42")), Map.of(ArgiNeutralAgents.STRUCTURED_OUTPUT_KEY, Map.of("n", 42))))));
        FakeTask task = new FakeTask("task-2");

        // Resumed, but nothing is suspended under this task: a fresh run with the task's history.
        ArgiNeutralAgents.handler(agent).handle(request("and now?", "task-2", true,
                List.of(AgentMessage.user("hi"), AgentMessage.agent("hello"))), task);

        assertThat(agent.requests.get(0).resumeToken()).isNull();
        assertThat(agent.requests.get(0).messages()).extracting(ChatMessage::role)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER);
        assertThat(task.events).containsExactly("artifact:response:42", "complete");
        assertThat(task.artifacts.get(0).parts()).containsExactly(new Content.Text("42"),
                new Content.Json(Map.of("n", 42)));

        ScriptedAgent noReplay = new ScriptedAgent(req -> List.of(new AgentEvent.Finished(completed(List.of(), Map.of()))));
        ArgiNeutralAgents.builder(noReplay).replayHistory(false).build()
                .handle(request("only", "task-3", false, List.of(AgentMessage.user("old"))), new FakeTask("task-3"));
        assertThat(noReplay.requests.get(0).messages()).extracting(ChatMessage::text).containsExactly("only");
    }

    @Test
    void answerFallsBackToStreamedTextAndCustomizerApplies() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(req -> List.of(new AgentEvent.MessageDelta("m", "a"),
                new AgentEvent.MessageDelta("m", "b"), new AgentEvent.Finished(completed(List.of(), Map.of()))));
        FakeTask task = new FakeTask("task-4");

        ArgiNeutralAgents.builder(agent)
                .requestCustomizer((request, argi) -> new io.github.agentic.ai.agent.AgentRequest(argi.messages(),
                        Map.of("custom", true), argi.resumeToken()))
                .build().handle(request("x", "task-4", false, List.of()), task);

        assertThat(task.events).containsExactly("working:a", "working:b", "artifact:response:ab", "complete");
        assertThat(agent.requests.get(0).inputs()).isEqualTo(Map.of("custom", true));
    }

    @Test
    void errorsAndMissingOutcomesFailTheTask() throws Exception {
        FakeTask errored = new FakeTask("t");
        FakeTask noOutcome = new FakeTask("t");
        FakeTask blank = new FakeTask("t");
        FakeTask throwing = new FakeTask("t");

        ArgiNeutralAgents.handler(new ScriptedAgent(req -> List.of(new AgentEvent.MessageDelta("m", "x"),
                new IllegalStateException("model down")))).handle(request("q", "t", false, List.of()), errored);
        ArgiNeutralAgents.handler(new ScriptedAgent(req -> List.of()))
                .handle(request("q", "t", false, List.of()), noOutcome);
        ScriptedAgent unused = new ScriptedAgent(req -> List.of());
        ArgiNeutralAgents.handler(unused).handle(request("  ", "t", false, List.of()), blank);
        ArgiNeutralAgents.handler((req, ctx) -> { throw new IllegalArgumentException("no resume support"); })
                .handle(request("q", "t", false, List.of()), throwing);

        assertThat(errored.events).containsExactly("working:x", "fail:model down");
        assertThat(noOutcome.events).containsExactly("fail:the agent finished without an outcome");
        assertThat(blank.events).containsExactly("fail:the message has no content");
        assertThat(unused.requests).isEmpty();
        assertThat(throwing.events).containsExactly("fail:no resume support");
    }

    @Test
    void cancellingTheTaskCancelsTheSubscription() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(req -> List.of(new AgentEvent.MessageDelta("m", "started"),
                ScriptedAgent.HANG));
        FakeTask task = new FakeTask("task-5");
        task.cancelWhen = e -> e.equals("working:started");

        ArgiNeutralAgents.handler(agent).handle(request("long job", "task-5", false, List.of()), task);

        assertThat(agent.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(task.events).containsExactly("working:started");
        assertThat(agent.contexts.get(0).control().deadline()).isEmpty();
    }

    @Test
    void onCancelStopsARunningTaskAndForgetsASuspension() throws Exception {
        Map<String, ArgiNeutralAgents.SuspendedRun> store = new ConcurrentHashMap<>();
        ScriptedAgent agent = new ScriptedAgent(req -> List.of(ScriptedAgent.HANG));
        AgentHandler handler = ArgiNeutralAgents.builder(agent).suspensions(store).build();
        FakeTask task = new FakeTask("task-6");

        Thread worker = new Thread(() -> {
            try {
                handler.handle(request("q", "task-6", false, List.of()), task);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        worker.start();
        while (agent.requests.isEmpty()) {
            Thread.sleep(10);
        }
        Thread.sleep(50);
        store.put("task-6", new ArgiNeutralAgents.SuspendedRun("old", List.of(), Map.of(), Map.of()));
        task.cancelled = true;
        handler.onCancel("task-6");
        worker.join(5000);

        assertThat(agent.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isAlive()).isFalse();
        assertThat(store).isEmpty();
        assertThat(task.events).isEmpty();
    }
}
