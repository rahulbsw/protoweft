package io.agentweave.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake LLM: asks for one tool call, then answers with the tool's result. Records every request.
 */
final class ScriptedChatModel implements ChatModel {

    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final String toolName;
    private final String arguments;

    ScriptedChatModel(String toolName, String arguments) {
        this.toolName = toolName;
        this.arguments = arguments;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        ChatMessage last = request.messages().get(request.messages().size() - 1);
        if (last instanceof ToolExecutionResultMessage result) {
            String verdict = Boolean.TRUE.equals(result.isError()) ? "Tool failed: " : "The answer is ";
            return ChatResponse.builder().aiMessage(AiMessage.from(verdict + result.text()))
                    .finishReason(FinishReason.STOP).build();
        }
        if (toolName == null) {
            return ChatResponse.builder().aiMessage(AiMessage.from("echo: " + ((UserMessage) last).singleText()))
                    .finishReason(FinishReason.STOP).build();
        }
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("call-1").name(toolName).arguments(arguments).build();
        return ChatResponse.builder().aiMessage(AiMessage.from(call)).finishReason(FinishReason.TOOL_EXECUTION).build();
    }

    @Override
    public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
        return CompletableFuture.supplyAsync(() -> doChat(request));
    }

    ToolExecutionResultMessage lastToolResult() {
        List<ChatMessage> messages = requests.get(requests.size() - 1).messages();
        return (ToolExecutionResultMessage) messages.get(messages.size() - 1);
    }
}
