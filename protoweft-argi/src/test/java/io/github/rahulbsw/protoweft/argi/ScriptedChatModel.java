package io.github.rahulbsw.protoweft.argi;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A chat model that plays a script: each call answers with the next step (tool calls or text),
 * computed from the prompt it received. Every prompt is recorded, so tests can check which tool
 * definitions and tool results the model was given. No network. (The same idea as
 * protoweft-spring-ai's test model; ARGI drives it through its own ChatClient.)
 */
final class ScriptedChatModel implements ChatModel {

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final Deque<Function<Prompt, AssistantMessage>> script = new ArrayDeque<>();

    @SafeVarargs
    ScriptedChatModel(Function<Prompt, AssistantMessage>... steps) {
        script.addAll(List.of(steps));
    }

    static Function<Prompt, AssistantMessage> callTool(String name, String arguments) {
        return calls(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments));
    }

    static Function<Prompt, AssistantMessage> calls(AssistantMessage.ToolCall... calls) {
        return prompt -> AssistantMessage.builder().content("").toolCalls(Arrays.asList(calls)).build();
    }

    static Function<Prompt, AssistantMessage> answer(String text) {
        return prompt -> AssistantMessage.builder().content(text).build();
    }

    /** Answers with every tool result of the latest tool response message. */
    static Function<Prompt, AssistantMessage> echoToolResults() {
        return prompt -> AssistantMessage.builder().content("result: " + String.join(" | ", toolResults(prompt))).build();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Function<Prompt, AssistantMessage> step = script.poll();
        AssistantMessage message = step == null ? AssistantMessage.builder().content("done").build() : step.apply(prompt);
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** Streams a scripted tool call as one response and a text answer word by word. */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse response = call(prompt);
        if (response.hasToolCalls()) {
            return Flux.just(response);
        }
        String text = response.getResult().getOutput().getText();
        return Flux.fromArray(text.split("(?<= )"))
                .map(w -> new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(w).build()))));
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    /** Names of the tool definitions offered in prompt {@code i}. */
    List<String> toolNames(int i) {
        if (!(prompts.get(i).getOptions() instanceof ToolCallingChatOptions options) || options.getToolCallbacks() == null) {
            return List.of();
        }
        return options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition).map(d -> d.name()).sorted().toList();
    }

    /** The results in the latest tool response message of a prompt. */
    static List<String> toolResults(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage tool) {
                return tool.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData).toList();
            }
        }
        return List.of();
    }

    /** The tool results the last prompt carried. */
    List<String> lastToolResults() {
        return toolResults(prompts.get(prompts.size() - 1));
    }
}
