package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.AssistantMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Serving core {@link AgentHandler}s over AG-UI, driven through the server's own run emitter. */
class HandlerAdapterTest {

    /** Runs {@code handler} for {@code input} the way the servlet does and returns the verified stream. */
    private static List<AgUiEvent> run(AgentHandler handler, RunAgentInput input) {
        List<AgUiEvent> events = new ArrayList<>();
        RunEmitter emitter = new RunEmitter(input, CallerIdentity.of("bob"), events::add);
        emitter.start();
        try {
            AgUi.agent(handler).run(input, emitter);
            emitter.endIfOpen();
        } catch (Exception e) {
            emitter.failIfOpen(e.getMessage());
        }
        StreamVerifier verifier = new StreamVerifier();
        for (AgUiEvent e : events) {
            assertThat(OfficialSchema.errors("Event", EventCodec.encode(e))).isEmpty();
            verifier.accept(e);
        }
        return events;
    }

    private static RunAgentInput ask(String text) {
        return RunAgentInput.of("thread-1", "run-1", List.of(UserMessage.text("u1", text)));
    }

    private static AgUiEvent last(List<AgUiEvent> events) {
        return events.get(events.size() - 1);
    }

    @Test
    void streamingHandlerBecomesTextAndToolCallEvents() {
        List<AgUiEvent> events = run((request, task) -> {
            task.working("searching");
            task.textDelta("Let me ");
            task.textDelta("check.");
            task.toolCall("c1", "weather", "{\"city\":\"Oslo\"}");
            task.toolResult("c1", ToolOutcome.text("21.5"));
            task.textDelta("It is 21.5.");
            task.complete();
        }, ask("weather in Oslo?"));

        assertThat(TestClient.types(events)).containsExactly("RUN_STARTED", "ACTIVITY_SNAPSHOT",
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END",
                "TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END", "TOOL_CALL_RESULT",
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
        assertThat(((ActivitySnapshot) events.get(1)).content()).isEqualTo(Map.of("text", "searching"));
        assertThat(((ToolCallResult) events.get(9)).content()).isEqualTo("21.5");
        assertThat(((RunFinished) last(events)).outcome()).isNull();
    }

    @Test
    void requestCarriesTheLastUserMessageAndItsHistory() {
        AtomicReference<AgentRequest> seen = new AtomicReference<>();
        run((request, task) -> {
            seen.set(request);
            task.reply("ok");
        }, RunAgentInput.of("thread-1", "run-2", List.of(UserMessage.text("u1", "first"),
                AssistantMessage.text("a1", "answer"), UserMessage.text("u2", "second"))));

        AgentRequest request = seen.get();
        assertThat(request.text()).isEqualTo("second");
        assertThat(request.taskId()).isEqualTo("thread-1");
        assertThat(request.contextId()).isEqualTo("thread-1");
        assertThat(request.resumed()).isFalse();
        assertThat(request.history()).extracting(m -> m.text()).containsExactly("first", "answer");
        assertThat(request.caller().name()).isEqualTo("bob");
    }

    @Test
    void requireInputInterruptsAndResumeEntriesResumeTheTask() {
        List<AgUiEvent> first = run((request, task) -> task.requireInput("For how many people?"), ask("book a table"));
        RunOutcome.Interrupted outcome = (RunOutcome.Interrupted) ((RunFinished) last(first)).outcome();
        Interrupt interrupt = outcome.interrupts().get(0);
        assertThat(interrupt.reason()).isEqualTo("input_required");
        assertThat(interrupt.message()).isEqualTo("For how many people?");

        AtomicReference<AgentRequest> resumed = new AtomicReference<>();
        RunAgentInput answer = new RunAgentInput("thread-1", "run-2", "1.0", null, null,
                List.of(UserMessage.text("u1", "book a table")), null, null, null,
                List.of(new RunAgentInput.ResumeEntry(interrupt.id(), "resolved", "four", null)));
        run((request, task) -> {
            resumed.set(request);
            task.complete("Booked for " + request.text());
        }, answer);

        assertThat(resumed.get().resumed()).isTrue();
        assertThat(resumed.get().text()).isEqualTo("four");
        assertThat(resumed.get().taskId()).isEqualTo("thread-1");
        assertThat(resumed.get().history()).extracting(m -> m.text()).containsExactly("book a table");
        assertThat(resumed.get().message().metadata()).containsKey(HandlerAdapter.RESUME_METADATA);
    }

    @Test
    void failRejectAndExceptionsEndWithRunError() {
        assertThat(last(run((r, t) -> t.fail("no model"), ask("x")))).isEqualTo(new RunError("no model", null, null, Envelope.NONE));
        assertThat(last(run((r, t) -> t.reject("not my job"), ask("x"))))
                .isEqualTo(new RunError("not my job", "rejected", null, Envelope.NONE));
        assertThat(last(run((r, t) -> {
            throw new IllegalStateException("kaboom");
        }, ask("x")))).isEqualTo(new RunError("kaboom", null, null, Envelope.NONE));
    }

    @Test
    void artifactsBecomeMessagesAndActivities() {
        List<AgUiEvent> events = run((request, task) -> {
            task.artifact("report", List.of(new Content.Text("line1"), new Content.Data("image/png", "aGk=", "a.png")));
            task.complete();
        }, ask("report"));
        assertThat(TestClient.types(events)).containsExactly("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END", "ACTIVITY_SNAPSHOT", "RUN_FINISHED");
        ActivitySnapshot artifact = (ActivitySnapshot) events.get(4);
        assertThat(artifact.activityType()).isEqualTo("artifact");
        assertThat(artifact.content()).containsEntry("name", "report");
    }

    @Test
    void handlersReachAgUiFeaturesThroughTheRun() {
        List<AgUiEvent> events = run((request, task) -> {
            assertThat(AgUi.run(task)).isPresent();
            AgUi.run(task).get().stateSnapshot(Map.of("step", 1));
            task.complete();
        }, ask("x"));
        assertThat(events.get(1)).isEqualTo(new StateSnapshot(Map.of("step", 1), Envelope.NONE));
        assertThat(AgUi.run(new NotAgUi())).isEmpty();
    }

    @Test
    void disconnectCallsOnCancel() throws Exception {
        List<String> cancelled = new ArrayList<>();
        AgentHandler handler = new AgentHandler() {
            @Override
            public void handle(AgentRequest request, AgentTaskContext task) {
                task.textDelta("a");
                task.textDelta("b");
                assertThat(task.isCancelled()).isTrue();
            }

            @Override
            public void onCancel(String taskId) {
                cancelled.add(taskId);
            }
        };
        int[] sent = {0};
        RunEmitter emitter = new RunEmitter(ask("x"), CallerIdentity.ANONYMOUS, e -> {
            if (++sent[0] > 2) {
                throw new IOException("broken pipe");
            }
        });
        emitter.start();
        AgUi.agent(handler).run(ask("x"), emitter);
        assertThat(cancelled).containsExactly("thread-1");
    }

    @Test
    void aDisconnectBeforeTheHandlerStartsStillCallsOnCancel() throws Exception {
        List<String> cancelled = new ArrayList<>();
        AgentHandler handler = new AgentHandler() {
            @Override
            public void handle(AgentRequest request, AgentTaskContext task) {
                assertThat(task.isCancelled()).isTrue();
            }

            @Override
            public void onCancel(String taskId) {
                cancelled.add(taskId);
            }
        };
        RunEmitter emitter = new RunEmitter(ask("x"), CallerIdentity.ANONYMOUS, e -> {
            throw new IOException("client gone before RUN_STARTED");
        });
        emitter.start();                       // the write fails: disconnected, no hook yet
        AgUi.agent(handler).run(ask("x"), emitter);
        emitter.disconnected();                // a later signal must not fire it again
        assertThat(cancelled).containsExactly("thread-1");
    }

    /** A task context from some other protocol. */
    private static final class NotAgUi implements AgentTaskContext {
        public String taskId() { return "t"; }
        public String contextId() { return "c"; }
        public void reply(io.github.rahulbsw.protoweft.core.agent.AgentMessage message) { }
        public void working(String status) { }
        public void artifact(io.github.rahulbsw.protoweft.core.agent.AgentArtifact artifact) { }
        public void requireInput(String question) { }
        public void requireAuth(String reason) { }
        public void complete(String message) { }
        public void fail(String reason) { }
        public void reject(String reason) { }
        public boolean isCancelled() { return false; }
    }
}
