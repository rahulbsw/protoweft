package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protoweft's client against an agent built on the official Python SDK (ag-ui-protocol),
 * {@code interop/agui/python/server.py}. Run by {@code scripts/interop/agui.sh}, which sets
 * {@code AGUI_INTEROP_PYTHON_URL}; skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "AGUI_INTEROP_PYTHON_URL", matches = ".+")
class OfficialPythonAgentInteropTest {

    private static final String URL = System.getenv("AGUI_INTEROP_PYTHON_URL");

    @Test
    void conversationWithChunksStateAndAFrontendTool() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        AgUiClient client = AgUiClient.builder(URL).warnings(warnings::add).build();
        AgUiThread thread = AgUiThread.builder(client).listener(events::add)
                .clientTools(List.of(new RunAgentInput.Tool("confirm", "Ask the user to confirm", null, null)),
                        call -> Optional.of("yes"))
                .build();

        AgUiThread.Turn turn = thread.send("hello");

        assertThat(turn.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(turn.text()).isEqualTo("you said: helloconfirmed: yes");   // two runs: reply, then tool result
        assertThat(thread.state()).isEqualTo(Map.of("turns", 2));
        assertThat(events).extracting(AgUiEvent::type).contains("REASONING_MESSAGE_CONTENT", "STEP_FINISHED",
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_END");   // the Python agent sends chunks; we expanded them
        assertThat(events).extracting(AgUiEvent::type).doesNotContain("TEXT_MESSAGE_CHUNK");
        assertThat(warnings).isEmpty();
    }

    @Test
    void interruptAndResume() {
        AgUiThread thread = AgUiThread.builder(AgUiClient.builder(URL).build()).build();
        AgUiThread.Turn asked = thread.send("interrupt");
        assertThat(asked.status()).isEqualTo(AgUiThread.Status.INTERRUPTED);
        assertThat(asked.interrupts()).extracting(AgUiEvent.Interrupt::id).containsExactly("approve-1");
        assertThat(thread.resume(Map.of("approve-1", "go")).text()).isEqualTo("resumed: go");
    }

    @Test
    void asAProtoweftAgent() {
        AgUiClientConnector agent = AgUiClientConnector.builder(URL).build();
        agent.start();
        AgentReply reply = agent.send(AgentMessage.user("hi"));
        assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(reply.text()).isEqualTo("you said: hi");
        agent.close();
    }

    @Test
    void theOfficialAgentPassesTheConformanceChecker() {
        AgUiConformance.Report report = AgUiConformance.builder(URI.create(URL)).build().run();
        assertThat(report.passed()).as(report.text()).isTrue();
    }
}
