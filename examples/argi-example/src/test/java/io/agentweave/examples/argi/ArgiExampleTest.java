package io.agentweave.examples.argi;

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentReply;
import io.agentweave.core.agent.TaskState;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The running example, called over A2A, with a scripted chat model instead of OpenAI (no network). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArgiExampleTest {

    @TestConfiguration
    static class ScriptedModelConfiguration {
        @Bean
        @Primary
        BankScript scriptedChatModel() {
            return new BankScript();
        }
    }

    /** Calls local__balance for "balance", local__transfer for "send <n> to <name>", then reports the tool result. */
    static final class BankScript implements ChatModel {
        private static final Pattern SEND = Pattern.compile("send (\\d+) to (\\w+)", Pattern.CASE_INSENSITIVE);
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        private final AtomicInteger ids = new AtomicInteger();

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
                Matcher send = SEND.matcher(text);
                if (send.find()) {
                    answer.content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-" + ids.incrementAndGet(),
                            "function", "local__transfer",
                            "{\"to\":\"" + send.group(2) + "\",\"amount\":" + send.group(1) + "}")));
                } else if (text.toLowerCase().contains("balance")) {
                    answer.content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-" + ids.incrementAndGet(),
                            "function", "local__balance", "{\"account\":\"main\"}")));
                } else {
                    answer.content("echo " + text);
                }
            }
            return new ChatResponse(List.of(new Generation(answer.build())));
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
    BankScript model;

    @Autowired
    BankTools tools;

    @BeforeEach
    void reset() {
        model.prompts.clear();
        tools.transfers.clear();
    }

    private A2aAgentConnector agent() {
        A2aAgentConnector agent = A2aAgentConnector.builder("http://localhost:" + port).build();
        agent.start();
        return agent;
    }

    @Test
    void catalogOffersOnlyTheMatchingTool() {
        try (A2aAgentConnector agent = agent()) {
            assertThat(agent.descriptor().name()).isEqualTo("argi-bank-assistant");

            AgentReply reply = agent.send(AgentMessage.user("What is the balance of my account?"));

            assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(reply.artifacts()).singleElement().satisfies(a -> assertThat(a.name()).isEqualTo("response"));
            assertThat(reply.text()).matches("Done: \\d+\\.0");
            assertThat(model.offeredTools(0)).contains("local__balance").doesNotContain("local__transfer");
        }
    }

    @Test
    void transferWaitsForApprovalThenRuns() {
        try (A2aAgentConnector agent = agent()) {
            AgentReply asked = agent.send(AgentMessage.user("Please send 25 to bob as a transfer"));
            assertThat(asked.state()).isEqualTo(TaskState.INPUT_REQUIRED);
            assertThat(asked.message().text()).contains("local__transfer", "{\"to\":\"bob\",\"amount\":25}",
                    "Moves real money");
            assertThat(tools.transfers).isEmpty();
            assertThat(model.offeredTools(0)).contains("local__transfer").doesNotContain("local__rate");

            AgentReply done = agent.send(AgentMessage.userReply(asked.taskId(), asked.contextId(), "approve"));

            assertThat(done.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(done.text()).isEqualTo("Done: Transferred 25 EUR to bob for anonymous");
            assertThat(tools.transfers).containsExactly("Transferred 25 EUR to bob for anonymous");
        }
    }

    @Test
    void rejectedTransferNeverRuns() {
        try (A2aAgentConnector agent = agent()) {
            AgentReply asked = agent.send(AgentMessage.user("Send 900 to mallory, a transfer"));
            AgentReply done = agent.send(AgentMessage.userReply(asked.taskId(), asked.contextId(), "reject not a payee"));

            assertThat(done.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(done.text()).contains("rejected").contains("not a payee");
            assertThat(tools.transfers).isEmpty();
        }
    }
}
