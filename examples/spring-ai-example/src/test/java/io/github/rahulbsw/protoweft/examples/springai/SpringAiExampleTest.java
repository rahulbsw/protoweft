package io.github.rahulbsw.protoweft.examples.springai;

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.core.agent.AgentEvent;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import io.github.rahulbsw.protoweft.core.tool.Content;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The running example, called over A2A, with a scripted chat model instead of OpenAI (no network). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringAiExampleTest {

    @TestConfiguration
    static class ScriptedModelConfiguration {
        @Bean
        @Primary
        WeatherScript scriptedChatModel() {
            return new WeatherScript();
        }
    }

    /** Calls local__temperature for "weather in <city>", local__whoami for "who am I", then reports the result. */
    static final class WeatherScript implements ChatModel {
        private static final Pattern CITY = Pattern.compile("weather in (\\w+)", Pattern.CASE_INSENSITIVE);
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            List<Message> messages = prompt.getInstructions();
            Message last = messages.get(messages.size() - 1);
            AssistantMessage.Builder<?> answer = AssistantMessage.builder();
            if (last instanceof ToolResponseMessage tool) {
                answer.content("The tool said " + tool.getResponses().get(0).responseData() + " °C in Paris.");
            } else {
                String text = ((UserMessage) last).getText();
                Matcher m = CITY.matcher(text);
                if (m.find()) {
                    answer.content("").toolCalls(List.of(new AssistantMessage.ToolCall("1", "function",
                            "local__temperature", "{\"city\":\"" + m.group(1) + "\"}")));
                } else if (text.toLowerCase().contains("who am i")) {
                    answer.content("").toolCalls(List.of(new AssistantMessage.ToolCall("2", "function",
                            "local__whoami", "{}")));
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

        List<String> offeredTools(int i) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompts.get(i).getOptions();
            return options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition).map(d -> d.name())
                    .sorted().toList();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    WeatherScript model;

    private A2aAgentConnector agent() {
        A2aAgentConnector agent = A2aAgentConnector.builder("http://localhost:" + port).build();
        agent.start();
        return agent;
    }

    @Test
    void agentAnswersWithProtoweftTool() {
        try (A2aAgentConnector agent = agent()) {
            assertThat(agent.descriptor().name()).isEqualTo("spring-ai-assistant");

            AgentReply reply = agent.send(AgentMessage.user("What is the weather in Paris?"));

            assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(reply.artifacts()).singleElement().satisfies(a -> assertThat(a.name()).isEqualTo("response"));
            assertThat(reply.text()).matches("(?s).*The tool said \\d+\\.0 °C in Paris\\..*");
            assertThat(model.offeredTools(0)).containsExactly("local__temperature", "local__time", "local__whoami");
        }
    }

    @Test
    void streamingReportsFragmentsAndCallerReachesTools() {
        List<String> chunks = new CopyOnWriteArrayList<>();
        try (A2aAgentConnector agent = agent()) {
            assertThat(agent.streams()).isTrue();

            AgentReply reply = agent.send(AgentMessage.user("Who am I?"), event -> {
                // Streamed text arrives as chunks of the "response" artifact.
                if (event instanceof AgentEvent.ArtifactUpdate a && !a.lastChunk()) {
                    chunks.add(((Content.Text) a.artifact().parts().get(0)).text());
                }
            });

            assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(reply.text()).contains("The tool said anonymous");
            assertThat(chunks).contains("The ", "tool ");
        }
    }

    @Test
    void conversationMemoryFollowsTheContext() {
        try (A2aAgentConnector agent = agent()) {
            AgentReply first = agent.send(AgentMessage.user("hello"));
            int before = model.prompts.size();
            agent.send(new AgentMessage(AgentMessage.Role.USER, List.of(new Content.Text("again")), null, null,
                    first.contextId(), null));

            // system + first exchange + new user message
            assertThat(model.prompts.get(before).getInstructions()).hasSizeGreaterThanOrEqualTo(4);
        }
    }
}
