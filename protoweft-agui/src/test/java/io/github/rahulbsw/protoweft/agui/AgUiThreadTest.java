package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.*;
import io.github.rahulbsw.protoweft.agui.AgUiThread.Status;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A Protoweft client conversing with a scripted Protoweft server. */
class AgUiThreadTest {

    /** Answers by the last message: scripted commands, tool results and resumes. */
    static final AgUiAgent AGENT = (in, run) -> {
        if (!in.resume().isEmpty()) {
            run.say("resumed: " + in.resume().get(0).status() + " " + in.resume().get(0).payload());
            run.finish();
            return;
        }
        AgUiMessage last = in.messages().get(in.messages().size() - 1);
        if (last instanceof ToolMessage tool) {
            run.say("tool said " + tool.content() + (tool.error() == null ? "" : " [" + tool.error() + "]"));
            run.finish();
            return;
        }
        String text = (String) ((UserMessage) last).content();
        switch (text) {
            case "count" -> {
                Object n = in.state() instanceof Map<?, ?> s ? s.get("n") : null;
                run.stateDelta(List.of(n == null ? JsonPatch.op("add", "/n", 1)
                        : JsonPatch.op("replace", "/n", ((Number) n).intValue() + 1)));
                run.say("counted");
            }
            case "approve?" -> run.interrupt(List.of(Interrupt.of("i1", "approval", "Proceed?")));
            case "expired" -> run.interrupt(List.of(new Interrupt("i1", "approval", "Too late?", null, null,
                    "2000-01-01T00:00:00Z", null, null)));
            case "weather" -> run.callClientTool("weather__forecast", "{\"city\":\"Oslo\"}");
            case "bad args" -> run.callClientTool("weather__forecast", "{\"town\":1}");
            case "delete" -> run.callClientTool("files__delete", "{\"path\":\"/tmp/x\"}");
            case "ui" -> run.callClientTool("confirm_dialog", "{}");
            default -> run.say("you said " + text + " (" + in.messages().size() + " messages)");
        }
        run.finish();
    };

    /** A forecast tool marked read-only and a delete tool that is not. */
    static final class Tools implements ToolSource {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public List<ToolSpec> listTools() {
            return List.of(
                    new ToolSpec("weather.forecast", "Forecast for a city", Map.of("type", "object",
                            "properties", Map.of("city", Map.of("type", "string")), "required", List.of("city")),
                            Map.of(ToolSpec.META_READ_ONLY, true)),
                    new ToolSpec("files.delete", "Delete a file", Map.of("type", "object"), Map.of()));
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation) {
            calls.add(invocation.toolName() + invocation.arguments());
            return ToolOutcome.text(invocation.toolName().equals("weather.forecast") ? "rain" : "deleted");
        }
    }

    private ProtoweftServer host;
    private AgUiAgentServer server;
    private AgUiClient client;
    private final Tools tools = new Tools();

    @BeforeEach
    void start() {
        server = AgUiAgentServer.builder(AGENT).build();
        host = ProtoweftServer.builder().port(0).endpoints(server).start();
        client = AgUiClient.builder(host.baseUrl() + "/agui").build();
    }

    @AfterEach
    void stop() {
        host.close();
        server.close();
    }

    @Test
    void historyAndStateTravelWithEveryRun() {
        AgUiThread thread = AgUiThread.builder(client).build();
        assertThat(thread.send("hello").text()).isEqualTo("you said hello (1 messages)");
        assertThat(thread.send("again").text()).isEqualTo("you said again (3 messages)");
        thread.send("count");
        thread.send("count");
        assertThat(thread.state()).isEqualTo(Map.of("n", 2));
        assertThat(thread.messages()).extracting(AgUiMessage::role)
                .containsExactly("user", "assistant", "user", "assistant", "user", "assistant", "user", "assistant");
    }

    @Test
    void interruptsMustBeCoveredBeforeContinuing() {
        AgUiThread thread = AgUiThread.builder(client).build();
        AgUiThread.Turn turn = thread.send("approve?");
        assertThat(turn.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(turn.interrupts()).extracting(Interrupt::message).containsExactly("Proceed?");
        assertThatThrownBy(() -> thread.send("something else")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> thread.resume(List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> thread.resume(Map.of("nope", true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(thread.resume(Map.of("i1", "yes")).text()).isEqualTo("resumed: resolved yes");

        AgUiThread late = AgUiThread.builder(client).build();
        late.send("expired");
        assertThatThrownBy(() -> late.resume(Map.of("i1", "yes"))).hasMessageContaining("expired");
        assertThat(late.resume(List.of(new RunAgentInput.ResumeEntry("i1", "cancelled", null, null))).text())
                .isEqualTo("resumed: cancelled null");
    }

    @Test
    void readOnlyBridgedToolsRunAutomatically() {
        AgUiThread thread = AgUiThread.builder(client).tools(tools, spec -> true).build();
        AgUiThread.Turn turn = thread.send("weather");
        assertThat(turn.status()).isEqualTo(Status.COMPLETED);
        assertThat(turn.text()).isEqualTo("tool said rain");
        assertThat(tools.calls).containsExactly("weather.forecast{city=Oslo}");
        assertThat(thread.messages()).extracting(AgUiMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void toolsThatChangeThingsNeedApproval() {
        AgUiThread unapproved = AgUiThread.builder(client).tools(tools, spec -> true).build();
        assertThat(unapproved.send("delete").text()).contains("approval required");
        assertThat(tools.calls).isEmpty();

        AgUiThread declined = AgUiThread.builder(client).tools(tools, spec -> true).approval(call -> false).build();
        assertThat(declined.send("delete").text()).contains("declined");
        assertThat(tools.calls).isEmpty();

        List<ToolApproval.Request> asked = new CopyOnWriteArrayList<>();
        AgUiThread approved = AgUiThread.builder(client).tools(tools, spec -> true)
                .approval(call -> asked.add(call)).caller(CallerIdentity.of("alice")).build();
        assertThat(approved.send("delete").text()).isEqualTo("tool said deleted");
        assertThat(asked).singleElement().satisfies(r -> assertThat(r.arguments()).isEqualTo(Map.of("path", "/tmp/x")));
        assertThat(tools.calls).containsExactly("files.delete{path=/tmp/x}");
    }

    @Test
    void argumentsAreCheckedBeforeAnythingRuns() {
        AgUiThread thread = AgUiThread.builder(client).tools(tools, spec -> true).build();
        assertThat(thread.send("bad args").text()).contains("invalid arguments: 'city' is required");
        assertThat(tools.calls).isEmpty();
    }

    @Test
    void applicationToolsGoToTheApplication() {
        RunAgentInput.Tool dialog = new RunAgentInput.Tool("confirm_dialog", "Ask the user", null, null);
        AgUiThread waiting = AgUiThread.builder(client).clientTools(List.of(dialog), call -> Optional.empty()).build();
        AgUiThread.Turn turn = waiting.send("ui");
        assertThat(turn.status()).isEqualTo(Status.AWAITING_TOOL_RESULTS);
        assertThat(turn.pendingCalls()).singleElement().satisfies(c -> assertThat(c.toolName()).isEqualTo("confirm_dialog"));
        assertThatThrownBy(() -> waiting.send("next")).isInstanceOf(IllegalStateException.class);
        waiting.answer(turn.pendingCalls().get(0).toolCallId(), "clicked OK", null);
        assertThat(waiting.continueRun().text()).isEqualTo("tool said clicked OK");

        AgUiThread handled = AgUiThread.builder(client)
                .clientTools(List.of(dialog), call -> Optional.of("auto-confirmed")).build();
        assertThat(handled.send("ui").text()).isEqualTo("tool said auto-confirmed");
    }

    @Test
    void connectorServesTheNeutralAgentContract() {
        AgUiClientConnector connector = AgUiClientConnector.builder(host.baseUrl() + "/agui").build();
        connector.start();
        List<String> events = new CopyOnWriteArrayList<>();
        AgentReply reply = connector.send(AgentMessage.user("hello"), e -> events.add(e.getClass().getSimpleName()));
        assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(reply.text()).isEqualTo("you said hello (1 messages)");
        assertThat(events).first().isEqualTo("StatusUpdate");
        assertThat(events).contains("ArtifactUpdate");

        AgentReply waiting = connector.send(AgentMessage.user("approve?"));
        assertThat(waiting.state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(waiting.text()).isEqualTo("Proceed?");
        AgentReply answered = connector.send(AgentMessage.userReply(waiting.taskId(), waiting.contextId(), "go"));
        assertThat(answered.text()).isEqualTo("resumed: resolved go");

        var outcome = connector.invoke(new ToolInvocation("ask", Map.of("message", "via tool")));
        assertThat(outcome.error()).isFalse();
        assertThat(connector.listTools()).extracting(ToolSpec::name).containsExactly("ask");
        assertThatThrownBy(() -> connector.getTask(reply.taskId()))
                .hasMessageContaining("no tasks");
        assertThat(connector.compliance().supports(AgUiFeatures.CHUNKS)).isTrue();
        connector.close();
    }

    @Test
    void theConnectorDoesNotReportATurnStoppedByTheRunLimitAsCompleted() {
        AgUiClientConnector connector = AgUiClientConnector.builder(host.baseUrl() + "/agui")
                .conversations(b -> b.tools(tools, spec -> true).maxAutomaticRuns(1)).build();
        connector.start();
        AgentReply stopped = connector.send(AgentMessage.user("weather"));
        assertThat(stopped.state()).isEqualTo(TaskState.WORKING);
        assertThat(stopped.text()).contains("automatic-run limit");
        assertThat(tools.calls).containsExactly("weather.forecast{city=Oslo}");   // ran, result not yet seen
        AgentReply finished = connector.send(AgentMessage.userReply(stopped.taskId(), stopped.contextId(), "go on"));
        assertThat(finished.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(finished.text()).isEqualTo("tool said rain");
        assertThat(connector.thread(stopped.taskId()).messages()).extracting(AgUiMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");   // "go on" was not added
        connector.close();
    }

    @Test
    void messagesSnapshotReconcilesById() {
        Transcript t = new Transcript(List.of(UserMessage.text("u1", "hi"), AssistantMessage.text("a1", "old"),
                new ActivityMessage("act", "progress", Map.of(), null, null), AssistantMessage.text("gone", "x")),
                null, w -> { });
        t.apply(new AgUiEvent.MessagesSnapshot(List.of(AssistantMessage.text("a1", "new"), UserMessage.text("u1", "hi"),
                AssistantMessage.text("a2", "added")), AgUiEvent.Envelope.NONE));
        assertThat(t.messages()).extracting(AgUiMessage::id).containsExactly("u1", "a1", "act", "a2");
        assertThat(((AssistantMessage) t.messages().get(1)).content()).isEqualTo("new");
    }
}
