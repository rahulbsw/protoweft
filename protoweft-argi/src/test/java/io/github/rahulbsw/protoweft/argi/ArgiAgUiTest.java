package io.github.rahulbsw.protoweft.argi;

import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.rahulbsw.protoweft.agui.AgUi;
import io.github.rahulbsw.protoweft.agui.AgUiAgentServer;
import io.github.rahulbsw.protoweft.agui.AgUiClient;
import io.github.rahulbsw.protoweft.agui.AgUiEvent;
import io.github.rahulbsw.protoweft.agui.AgUiThread;
import io.github.rahulbsw.protoweft.agui.RunAgentInput;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.rahulbsw.protoweft.argi.ScriptedChatModel.answer;
import static io.github.rahulbsw.protoweft.argi.ScriptedChatModel.callTool;
import static org.assertj.core.api.Assertions.assertThat;

/** ARGI agents served over AG-UI: approvals as interrupts, resume entries as decisions. */
class ArgiAgUiTest {

    private static final Map<String, Object> TRANSFER = Map.of("type", "object",
            "properties", Map.of("to", Map.of("type", "string"), "amount", Map.of("type", "integer")),
            "required", List.of("to", "amount"));

    private final List<String> transfers = new CopyOnWriteArrayList<>();
    private ProtoweftServer host;
    private AgUiAgentServer server;

    private AgUiThread thread(AgentHandler handler) {
        server = AgUiAgentServer.builder(AgUi.agent(handler)).build();
        host = ProtoweftServer.builder().port(0).endpoints(server).start();
        return AgUiThread.builder(AgUiClient.builder(host.baseUrl() + "/agui").build()).build();
    }

    private ReactAgent agent() {
        ToolRegistry bank = new ToolRegistry().register("bank", LocalTools.builder()
                .tool("transfer", "Transfers money", TRANSFER, (args, caller) -> {
                    transfers.add(args.get("to") + ":" + ((Number) args.get("amount")).intValue());
                    return ToolOutcome.text("sent");
                })
                .build());
        ScriptedChatModel model = new ScriptedChatModel(callTool("bank__transfer", "{\"to\":\"bob\",\"amount\":10}"),
                answer("Sent the money to bob."));
        return ReactAgent.builder().name("payments").model(model)
                .toolCallbackProviders(ArgiTools.callbackProvider(bank))
                .hooks(HumanInTheLoopHook.builder().approvalOn("bank__transfer", "Moves real money").build())
                .saver(new MemorySaver())
                .build();
    }

    @AfterEach
    void stop() {
        host.close();
        server.close();
    }

    @Test
    void approvalsAreInterruptsAndResumeEntriesDecide() {
        AgUiThread thread = thread(ArgiAgents.streamingHandler(agent()));

        AgUiThread.Turn asked = thread.send("pay bob 10");
        assertThat(asked.status()).isEqualTo(AgUiThread.Status.INTERRUPTED);
        AgUiEvent.Interrupt interrupt = asked.interrupts().get(0);
        assertThat(interrupt.reason()).isEqualTo(ArgiAgUi.REASON);
        assertThat(interrupt.metadata()).containsEntry("toolName", "bank__transfer");
        assertThat(interrupt.responseSchema()).containsKey("properties");
        assertThat(transfers).isEmpty();

        AgUiThread.Turn done = thread.resume(Map.of(interrupt.id(), Map.of("decision", "approved")));

        assertThat(done.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(done.text()).isEqualTo("Sent the money to bob.");
        assertThat(transfers).containsExactly("bob:10");
    }

    @Test
    void anAbandonedApprovalRejectsTheCall() {
        AgUiThread thread = thread(ArgiAgents.handler(agent()));
        AgUiEvent.Interrupt interrupt = thread.send("pay bob 10").interrupts().get(0);

        AgUiThread.Turn done = thread.resume(List.of(new RunAgentInput.ResumeEntry(interrupt.id(), "cancelled", null, null)));

        assertThat(done.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(transfers).isEmpty();
    }

    @Test
    void textAnswersWorkToo() {
        AgUiThread thread = thread(ArgiAgents.handler(agent()));
        AgUiEvent.Interrupt interrupt = thread.send("pay bob 10").interrupts().get(0);
        assertThat(thread.resume(Map.of(interrupt.id(), "approve")).status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(transfers).containsExactly("bob:10");
    }
}
