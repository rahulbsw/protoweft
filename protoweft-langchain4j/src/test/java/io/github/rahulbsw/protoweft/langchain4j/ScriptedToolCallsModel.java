package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake LLM following a script of tool calls: each model call makes the next scripted call, and
 * an {@link #ANSWER} step replies with the last tool result's text. Records every request.
 */
final class ScriptedToolCallsModel implements ChatModel {

    /** Script step: answer "The answer is &lt;last tool result&gt;". */
    static final String ANSWER = "<answer>";

    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<String[]> script = new ArrayDeque<>();
    private int callId;

    /** Appends a tool call step. */
    ScriptedToolCallsModel call(String toolName, String arguments) {
        script.add(new String[] {toolName, arguments});
        return this;
    }

    ScriptedToolCallsModel answer() {
        script.add(new String[] {ANSWER, null});
        return this;
    }

    @Override
    public synchronized ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        String[] step = script.poll();
        if (step == null) {
            throw new IllegalStateException("script exhausted");
        }
        if (step[0].equals(ANSWER)) {
            ChatMessage last = request.messages().get(request.messages().size() - 1);
            String text = last instanceof ToolExecutionResultMessage r ? r.text() : "nothing";
            return ChatResponse.builder().aiMessage(AiMessage.from("The answer is " + text))
                    .finishReason(FinishReason.STOP).build();
        }
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("call-" + ++callId).name(step[0])
                .arguments(step[1]).build();
        return ChatResponse.builder().aiMessage(AiMessage.from(call)).finishReason(FinishReason.TOOL_EXECUTION).build();
    }

    /** Tool names offered in the {@code index}-th request. */
    List<String> toolsOffered(int index) {
        return requests.get(index).toolSpecifications().stream().map(ToolSpecification::name).toList();
    }

    /** The tool result message the {@code index}-th request ended with. */
    ToolExecutionResultMessage toolResultIn(int index) {
        List<ChatMessage> messages = requests.get(index).messages();
        return (ToolExecutionResultMessage) messages.get(messages.size() - 1);
    }
}
