package io.agentweave.discovery.anthropic;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One processed model response: the assistant message to append to the conversation (with
 * every content block unchanged, including {@code server_tool_use} and
 * {@code tool_search_tool_result} blocks of hosted search) and the tool results to send back
 * in the next user message.
 *
 * @param assistantMessage the response as an assistant {@link MessageParam}
 * @param toolResults      one result per {@code tool_use} block, in response order
 */
public record AnthropicTurn(MessageParam assistantMessage, List<ToolResultBlockParam> toolResults) {

    public AnthropicTurn {
        Objects.requireNonNull(assistantMessage, "assistantMessage");
        toolResults = List.copyOf(toolResults);
    }

    /** Whether the response asked for tool calls, so a follow-up request is needed. */
    public boolean hasToolResults() {
        return !toolResults.isEmpty();
    }

    /** A user message carrying every tool result, or empty when there are none. */
    public Optional<MessageParam> toolResultMessage() {
        if (toolResults.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MessageParam.ofUserOfContentBlockParams(
                toolResults.stream().map(ContentBlockParam::ofToolResult).toList()));
    }

    /** Appends the assistant message and, if any, the tool-result user message. */
    public MessageCreateParams.Builder appendTo(MessageCreateParams.Builder request) {
        request.addMessage(assistantMessage);
        toolResultMessage().ifPresent(request::addMessage);
        return request;
    }
}
