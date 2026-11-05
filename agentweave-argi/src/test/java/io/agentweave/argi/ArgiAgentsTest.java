package io.agentweave.argi;

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.core.agent.AgentEvent;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentReply;
import io.agentweave.core.agent.TaskState;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.SourcePolicy;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.agentweave.argi.ScriptedChatModel.answer;
import static io.agentweave.argi.ScriptedChatModel.callTool;
import static io.agentweave.argi.ScriptedChatModel.calls;
import static io.agentweave.argi.ScriptedChatModel.echoToolResults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ARGI agents served over a real A2A server and called over HTTP; human-in-the-loop as input-required. */
class ArgiAgentsTest {

    private static final Map<String, Object> TRANSFER = Map.of("type", "object",
            "properties", Map.of("to", Map.of("type", "string"), "amount", Map.of("type", "integer")),
            "required", List.of("to", "amount"));
    private static final String TRANSFER_10 = "{\"to\":\"bob\",\"amount\":10}";

    private final List<String> transfers = new CopyOnWriteArrayList<>();

    private ToolRegistry bank() {
        return new ToolRegistry().register("bank", LocalTools.builder()
                .tool("transfer", "Transfers money", TRANSFER, (args, caller) -> {
                    String done = args.get("to") + ":" + ((Number) args.get("amount")).intValue() + " by " + caller.name();
                    transfers.add(done);
                    return ToolOutcome.text("sent " + done);
                })
                .build());
    }

    private ReactAgent agent(ScriptedChatModel model) {
        return ReactAgent.builder().name("payments").model(model)
                .toolCallbackProviders(ArgiTools.callbackProvider(bank()))
                .hooks(HumanInTheLoopHook.builder().approvalOn("bank__transfer", "Moves real money").build())
                .saver(new MemorySaver())
                .build();
    }

    private static AgentMessage reply(AgentReply to, Object json) {
        return new AgentMessage(AgentMessage.Role.USER, List.of(new Content.Json(json)), null, to.taskId(),
                to.contextId(), null);
    }

    @SafeVarargs
    private AgentReply[] conversation(AgentHandler handler, String token, Function<AgentReply, AgentMessage>... replies) {
        try (A2aHarness server = new A2aHarness(handler); A2aAgentConnector client = server.client(token)) {
            AgentReply[] out = new AgentReply[replies.length + 1];
            out[0] = client.send(AgentMessage.user("pay bob 10"));
            for (int i = 0; i < replies.length; i++) {
                out[i + 1] = client.send(replies[i].apply(out[i]));
            }
            return out;
        }
    }

    @Test
    void approvalPausesAsInputRequiredAndApproveRunsTheTool() {
        ScriptedChatModel model = new ScriptedChatModel(callTool("bank__transfer", TRANSFER_10), echoToolResults());

        AgentReply[] r = conversation(ArgiAgents.handler(agent(model)), "alice-token",
                first -> AgentMessage.userReply(first.taskId(), first.contextId(), "approve"));

        assertThat(r[0].state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(r[0].message().text()).contains("bank__transfer", "id call-bank__transfer", TRANSFER_10,
                "Moves real money", "Reply \"approve\"");
        assertThat(r[1].taskId()).isEqualTo(r[0].taskId());
        assertThat(r[1].state()).isEqualTo(TaskState.COMPLETED);
        assertThat(r[1].artifacts()).singleElement().satisfies(a -> assertThat(a.name()).isEqualTo("response"));
        assertThat(r[1].text()).isEqualTo("result: sent bob:10 by alice");
        assertThat(transfers).containsExactly("bob:10 by alice");
    }

    @Test
    void rejectTellsTheModelWhyAndSkipsTheTool() {
        ScriptedChatModel model = new ScriptedChatModel(callTool("bank__transfer", TRANSFER_10), echoToolResults());

        AgentReply[] r = conversation(ArgiAgents.handler(agent(model)), null,
                first -> AgentMessage.userReply(first.taskId(), first.contextId(), "reject too much money"));

        assertThat(r[1].state()).isEqualTo(TaskState.COMPLETED);
        assertThat(transfers).isEmpty();
        assertThat(r[1].text()).contains("rejected").contains("too much money");
    }

    @Test
    void editedArgumentsFromAJsonDataPart() {
        ScriptedChatModel model = new ScriptedChatModel(callTool("bank__transfer", TRANSFER_10), echoToolResults());

        AgentReply[] r = conversation(ArgiAgents.handler(agent(model)), null,
                first -> reply(first, Map.of("decision", "edited", "id", "call-bank__transfer",
                        "arguments", Map.of("to", "bob", "amount", 5))));

        assertThat(r[1].state()).isEqualTo(TaskState.COMPLETED);
        assertThat(transfers).containsExactly("bob:5 by anonymous");
    }

    @Test
    void severalPendingCallsAreDecidedOneByOne() {
        ScriptedChatModel model = new ScriptedChatModel(calls(
                new AssistantMessage.ToolCall("t1", "function", "bank__transfer", TRANSFER_10),
                new AssistantMessage.ToolCall("t2", "function", "bank__transfer", "{\"to\":\"carol\",\"amount\":20}")),
                echoToolResults());

        AgentReply[] r = conversation(ArgiAgents.handler(agent(model)), null,
                first -> reply(first, Map.of("decision", "approved", "id", "t1")),
                second -> AgentMessage.userReply(second.taskId(), second.contextId(), "maybe"),
                third -> reply(third, List.of(Map.of("decision", "rejected", "id", "t2", "reason", "not carol"))));

        assertThat(r[0].message().text()).contains("2 tools", "id t1", "id t2");
        assertThat(r[1].state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(r[1].message().text()).contains("id t2").doesNotContain("id t1");
        assertThat(r[2].state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(r[2].message().text()).startsWith("The reply was not understood.").contains("id t2");
        assertThat(r[3].state()).isEqualTo(TaskState.COMPLETED);
        assertThat(transfers).containsExactly("bob:10 by anonymous");
        assertThat(model.lastToolResults()).hasSize(2).anySatisfy(t -> assertThat(t).contains("not carol"));
    }

    @Test
    void streamingHandlerReportsChunksAndResumesApprovals() {
        ScriptedChatModel model = new ScriptedChatModel(callTool("bank__transfer", TRANSFER_10),
                answer("Sent the money to bob."));
        List<String> working = new CopyOnWriteArrayList<>();

        try (A2aHarness server = new A2aHarness(ArgiAgents.streamingHandler(agent(model)));
             A2aAgentConnector client = server.client(null)) {
            AgentReply first = client.send(AgentMessage.user("pay bob 10"), e -> { });
            assertThat(first.state()).isEqualTo(TaskState.INPUT_REQUIRED);

            AgentReply second = client.send(AgentMessage.userReply(first.taskId(), first.contextId(), "yes"), event -> {
                if (event instanceof AgentEvent.StatusUpdate s && s.state() == TaskState.WORKING && s.message() != null) {
                    working.add(s.message().text());
                }
            });

            assertThat(second.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(second.text()).isEqualTo("Sent the money to bob.");
            assertThat(working).contains("Sent ", "the ", "bob.");
            assertThat(transfers).containsExactly("bob:10 by anonymous");
        }
    }

    @Test
    void modelErrorsFailTheTaskWhenTheAgentThrowsThem() {
        Function<Prompt, AssistantMessage> down = prompt -> {
            throw new IllegalStateException("model is down");
        };
        ReactAgent throwing = ReactAgent.builder().name("payments").model(new ScriptedChatModel(down))
                .throwOnModelError(true).saver(new MemorySaver()).build();
        ReactAgent lenient = ReactAgent.builder().name("payments").model(new ScriptedChatModel(down))
                .saver(new MemorySaver()).build();

        try (A2aHarness server = new A2aHarness(ArgiAgents.handler(throwing));
             A2aAgentConnector client = server.client(null)) {
            AgentReply failed = client.send(AgentMessage.user("pay"));
            assertThat(failed.state()).isEqualTo(TaskState.FAILED);
            assertThat(failed.text()).contains("model is down");
        }
        // ARGI's default turns a model error into an assistant message, so the task completes with it.
        try (A2aHarness server = new A2aHarness(ArgiAgents.handler(lenient));
             A2aAgentConnector client = server.client(null)) {
            AgentReply answered = client.send(AgentMessage.user("pay"));
            assertThat(answered.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(answered.text()).isEqualTo("Exception: model is down");
        }
    }

    @Test
    void aReplyTheHandlerHasNoPausedRunForFailsClearly() {
        ScriptedChatModel model = new ScriptedChatModel(callTool("bank__transfer", TRANSFER_10), echoToolResults());
        ReactAgent agent = agent(model);
        AgentHandler before = ArgiAgents.handler(agent);
        AgentHandler afterRestart = ArgiAgents.handler(agent);
        AtomicReference<AgentHandler> current = new AtomicReference<>(before);

        try (A2aHarness server = new A2aHarness((request, task) -> current.get().handle(request, task));
             A2aAgentConnector client = server.client(null)) {
            AgentReply first = client.send(AgentMessage.user("pay bob 10"));
            assertThat(first.state()).isEqualTo(TaskState.INPUT_REQUIRED);
            current.set(afterRestart);

            AgentReply second = client.send(AgentMessage.userReply(first.taskId(), first.contextId(), "approve"));
            assertThat(second.state()).isEqualTo(TaskState.FAILED);
            assertThat(second.text()).contains("no paused ARGI run for task " + first.taskId());
            assertThat(transfers).isEmpty();
        }
    }

    @Test
    void catalogSelectionAndToolsSeeTheA2aCaller() {
        CapabilityCatalog catalog = CapabilityCatalog.builder()
                .source("bank", bank().sources().get("bank"), SourcePolicy.builder().requiredScopes("payments").build())
                .fallback(FallbackPolicy.of(FallbackPolicy.Mode.NONE))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(answer("cannot"), callTool("bank__transfer", TRANSFER_10),
                echoToolResults());
        ReactAgent agent = ReactAgent.builder().name("payments").model(model)
                .interceptors(CatalogToolSelectionInterceptor.from(catalog))
                .saver(new MemorySaver())
                .build();

        try (A2aHarness server = new A2aHarness(ArgiAgents.handler(agent));
             A2aAgentConnector anonymous = server.client(null);
             A2aAgentConnector alice = server.client("alice-token")) {
            anonymous.send(AgentMessage.user("transfer money"));
            assertThat(model.toolNames(0)).isEmpty();

            AgentReply paid = alice.send(AgentMessage.user("transfer money"));
            assertThat(model.toolNames(1)).containsExactly("bank__transfer");
            assertThat(paid.text()).isEqualTo("result: sent bob:10 by alice");
        }
    }

    @Test
    void anAgentWithoutASaverIsRejected() {
        ReactAgent noSaver = ReactAgent.builder().name("x").model(new ScriptedChatModel()).build();
        assertThatThrownBy(() -> ArgiAgents.handler(noSaver))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MemorySaver");
    }
}
