package io.github.rahulbsw.protoweft.examples.agui;

import io.github.rahulbsw.protoweft.agui.AgUiClient;
import io.github.rahulbsw.protoweft.agui.AgUiEvent;
import io.github.rahulbsw.protoweft.agui.AgUiThread;
import io.github.rahulbsw.protoweft.agui.RunAgentInput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The running example, driven over AG-UI with a scripted chat model instead of OpenAI (no network). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgUiWebExampleTest {

    @TestConfiguration
    static class ScriptedModelConfiguration {
        @Bean
        @Primary
        Script scriptedChatModel() {
            return new Script();
        }
    }

    /** "temperature in <city>" → local__temperature; "make it <colour>" → set_theme; then reports the tool result. */
    static final class Script implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            List<Message> messages = prompt.getInstructions();
            Message last = messages.get(messages.size() - 1);
            AssistantMessage.Builder<?> answer = AssistantMessage.builder();
            if (last instanceof ToolResponseMessage tool) {
                answer.content("Done: " + tool.getResponses().get(0).responseData());
            } else {
                String text = ((UserMessage) last).getText();
                if (text.startsWith("temperature in ")) {
                    answer.content("").toolCalls(List.of(new AssistantMessage.ToolCall("1", "function",
                            "local__temperature", "{\"city\":\"" + text.substring(15) + "\"}")));
                } else if (text.startsWith("make it ")) {
                    answer.content("").toolCalls(List.of(new AssistantMessage.ToolCall("2", "function",
                            "set_theme", "{\"color\":\"" + text.substring(8) + "\"}")));
                } else {
                    answer.content("echo " + text);
                }
            }
            return new ChatResponse(List.of(new Generation(answer.build())));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            ChatResponse response = call(prompt);
            if (response.hasToolCalls()) {
                return Flux.just(response);
            }
            return Flux.fromArray(response.getResult().getOutput().getText().split("(?<= )"))
                    .map(w -> new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(w).build()))));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    Script model;

    private AgUiThread thread(List<AgUiEvent> events) {
        AgUiClient client = AgUiClient.builder("http://localhost:" + port + "/agui").build();
        return AgUiThread.builder(client).listener(events::add)
                .clientTools(List.of(new RunAgentInput.Tool("set_theme", "Change the page's accent colour",
                        Map.of("type", "object", "properties", Map.of("color", Map.of("type", "string"))), null)),
                        call -> Optional.of("The page is now " + call.arguments().replaceAll(".*\"color\":\"([^\"]+)\".*", "$1") + "."))
                .build();
    }

    @Test
    void theAgentsOwnToolCallsAreShown() {
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        AgUiThread.Turn turn = thread(events).send("temperature in Oslo");
        assertThat(turn.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(turn.text()).matches("Done: \\d+\\.0");
        assertThat(events).filteredOn(AgUiEvent.ToolCallStart.class::isInstance)
                .extracting(e -> ((AgUiEvent.ToolCallStart) e).toolCallName()).containsExactly("local__temperature");
    }

    @Test
    void thePagesToolIsCalledAndItsResultReachesTheModel() {
        AgUiThread.Turn turn = thread(new CopyOnWriteArrayList<>()).send("make it teal");
        assertThat(turn.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(turn.text()).isEqualTo("Done: The page is now teal.");
    }

    @Test
    void thePageAndTheCapabilitiesAreServed() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("app.js");
        HttpResponse<String> caps = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/agui/capabilities")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(caps.body()).contains("Protoweft web assistant");
    }
}
