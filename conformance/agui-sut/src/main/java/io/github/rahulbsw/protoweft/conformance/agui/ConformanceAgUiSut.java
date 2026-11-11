package io.github.rahulbsw.protoweft.conformance.agui;

import io.github.rahulbsw.protoweft.agui.AgUiAgent;
import io.github.rahulbsw.protoweft.agui.AgUiAgentServer;
import io.github.rahulbsw.protoweft.agui.AgUiEmitter;
import io.github.rahulbsw.protoweft.agui.AgUiEvent;
import io.github.rahulbsw.protoweft.agui.AgUiMessage;
import io.github.rahulbsw.protoweft.agui.AgUiRun;
import io.github.rahulbsw.protoweft.agui.JsonPatch;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;

import java.util.List;
import java.util.Map;

/**
 * Serves a scripted AG-UI agent at {@code http://localhost:<AGUI_SUT_PORT>/agui} (default 9998):
 *
 * <ul>
 *   <li>a user message gets every event family: a step, a reasoning span, an agent-side tool call
 *       with its result, a state snapshot and delta, an activity, a subagent, and a streamed reply
 *       ({@code "you said: <text>"});</li>
 *   <li>if the application offered a tool named {@code confirm}, the agent calls it and leaves it
 *       pending; the next run, carrying the tool result, gets {@code "confirmed: <result>"};</li>
 *   <li>the message {@code "interrupt"} ends the run with an interrupt; resuming it gets
 *       {@code "resumed: <payload>"}.</li>
 * </ul>
 */
public final class ConformanceAgUiSut {

    static final AgUiAgent AGENT = (input, run) -> {
        if (!input.resume().isEmpty()) {
            run.say("resumed: " + input.resume().get(0).payload());
            run.finish();
            return;
        }
        AgUiMessage last = input.messages().isEmpty() ? null : input.messages().get(input.messages().size() - 1);
        if (last instanceof AgUiMessage.ToolMessage tool) {
            run.say("confirmed: " + tool.content());
            run.finish();
            return;
        }
        String text = last instanceof AgUiMessage.UserMessage u && u.content() instanceof String s ? s : "";
        if (text.equals("interrupt")) {
            run.interrupt(List.of(AgUiEvent.Interrupt.of("approve-1", "approval", "Proceed?")));
            return;
        }
        everyFamily(run, text);
        if (input.tools().stream().anyMatch(t -> t.name().equals("confirm"))) {
            run.callClientTool("confirm", "{\"question\":\"Shall I go ahead?\"}");
        }
        run.finish(Map.of("echo", text));
    };

    private static void everyFamily(AgUiRun run, String text) {
        run.startStep("answer");
        AgUiEmitter.ReasoningSpan thinking = run.startReasoning();
        AgUiEmitter.TextMessage thought = thinking.startMessage();
        thought.delta("The user said something; I will echo it.");
        thought.end();
        thinking.end();
        String call = run.toolCall("lookup", "{\"query\":\"echo\"}");
        run.toolResult(call, "found");
        run.stateSnapshot(Map.of("turns", 1));
        run.stateDelta(List.of(JsonPatch.op("replace", "/turns", 2)));
        run.activity("progress-1", "progress", Map.of("percent", 50));
        run.activityDelta("progress-1", "progress", List.of(JsonPatch.op("replace", "/percent", 100)));
        AgUiRun.Subagent helper = run.startSubagent("formatter", "formats the reply");
        helper.say("formatted");
        helper.finish(null);
        AgUiEmitter.TextMessage reply = run.startMessage();
        reply.delta("you said: ");
        reply.delta(text);
        reply.end();
        run.custom("protoweft.conformance", Map.of("ok", true));
        run.finishStep("answer");
    }

    private ConformanceAgUiSut() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("AGUI_SUT_PORT", "9998"));
        AgUiAgentServer agui = AgUiAgentServer.builder(AGENT).allowedOrigins("*").build();
        ProtoweftServer server = ProtoweftServer.builder().port(port).endpoints(agui).start();
        System.out.println("AG-UI SUT listening on " + server.baseUrl() + "/agui");
        server.join();
    }
}
