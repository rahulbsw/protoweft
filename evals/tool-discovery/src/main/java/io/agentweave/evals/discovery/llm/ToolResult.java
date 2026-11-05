package io.agentweave.evals.discovery.llm;

import java.util.Objects;

/**
 * The answer to one tool call, sent back to the model on the next turn.
 *
 * @param callId  the {@link ToolCall#id()} being answered
 * @param content result text (for a search tool: the matches)
 * @param error   whether the call failed
 */
public record ToolResult(String callId, String content, boolean error) {

    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        content = content == null ? "" : content;
    }
}
