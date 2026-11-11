package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.agui.AgUi;
import io.github.rahulbsw.protoweft.agui.AgUiAgentServer;
import io.github.rahulbsw.protoweft.agui.AgUiClient;
import io.github.rahulbsw.protoweft.agui.AgUiEvent;
import io.github.rahulbsw.protoweft.agui.AgUiMessage;
import io.github.rahulbsw.protoweft.agui.AgUiThread;
import io.github.rahulbsw.protoweft.agui.RunAgentInput;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A ChatClient agent served over AG-UI, with the blocking and the streaming handler. */
class SpringAiAgUiTest {

    private ProtoweftServer host;
    private AgUiAgentServer server;

    private AgUiClient serve(AgentHandler handler) {
        server = AgUiAgentServer.builder(AgUi.agent(handler)).build();
        host = ProtoweftServer.builder().port(0).endpoints(server).start();
        return AgUiClient.builder(host.baseUrl() + "/agui").build();
    }

    private static AgentHandler handler(boolean streaming, ChatClient chat) {
        return streaming ? SpringAiAgents.streamingHandler(chat) : SpringAiAgents.handler(chat);
    }

    @AfterEach
    void stop() {
        if (host != null) {
            host.close();
            server.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theModelSeesTheFrontendsConversation(boolean streaming) {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.answer("Sunny all week"));
        AgUiClient client = serve(handler(streaming, ChatClient.create(model)));
        AgUiThread thread = AgUiThread.builder(client)
                .messages(List.of(new AgUiMessage.SystemMessage("s", "Be brief", null, null, null, null),
                        AgUiMessage.UserMessage.text("u1", "hi"), AgUiMessage.AssistantMessage.text("a1", "hello")))
                .context(List.of(new RunAgentInput.Context("city", "Oslo")))
                .build();

        AgUiThread.Turn turn = thread.send("weather?");

        assertThat(turn.text()).isEqualTo("Sunny all week");
        List<Message> sent = model.prompts.get(0).getInstructions();
        assertThat(sent).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM, MessageType.USER,
                MessageType.ASSISTANT, MessageType.USER, MessageType.SYSTEM);
        assertThat(sent.get(3).getText()).isEqualTo("weather?");
        assertThat(sent.get(4).getText()).contains("city: Oslo");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicationToolsAreHandedToTheFrontendAndTheirResultsReachTheModel(boolean streaming) {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.callTool("confirm", "{\"what\":\"book\"}"),
                ScriptedChatModel.echoLastToolResult());
        AgUiClient client = serve(handler(streaming, ChatClient.create(model)));
        List<String> seen = new CopyOnWriteArrayList<>();
        AgUiThread thread = AgUiThread.builder(client)
                .clientTools(List.of(new RunAgentInput.Tool("confirm", "Ask the user to confirm",
                        Map.of("type", "object", "properties", Map.of("what", Map.of("type", "string"))), null)),
                        call -> {
                            seen.add(call.toolName() + " " + call.arguments());
                            return Optional.of("yes");
                        })
                .build();

        AgUiThread.Turn turn = thread.send("book it");

        assertThat(seen).containsExactly("confirm {\"what\":\"book\"}");
        assertThat(turn.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(turn.text()).isEqualTo("result: yes");
        assertThat(model.toolNames(0)).contains("confirm");
        assertThat(model.prompts).hasSize(2);   // one model call per run: the tool call ended the first run
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protoweftToolCallsAreShownInTheStream(boolean streaming) {
        LocalTools tools = LocalTools.builder()
                .tool("lookup", "Looks things up", Map.of("type", "object"), (args, caller) -> ToolOutcome.text("42"))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.callTool("lookup", "{}"),
                ScriptedChatModel.echoLastToolResult());
        ChatClient chat = ChatClient.builder(model).defaultTools(ProtoweftToolCallbacks.from(tools)).build();
        AgUiClient client = serve(handler(streaming, chat));
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();

        AgUiThread.Turn turn = AgUiThread.builder(client).listener(events::add).build().send("what is it?");

        assertThat(turn.text()).isEqualTo("result: 42");
        assertThat(events).filteredOn(AgUiEvent.ToolCallStart.class::isInstance)
                .extracting(e -> ((AgUiEvent.ToolCallStart) e).toolCallName()).containsExactly("lookup");
        assertThat(events).filteredOn(AgUiEvent.ToolCallResult.class::isInstance)
                .extracting(e -> ((AgUiEvent.ToolCallResult) e).content()).containsExactly("42");
    }
}
