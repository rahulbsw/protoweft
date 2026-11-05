package io.agentweave.mcp;

import java.util.List;

/**
 * A prompt rendered by {@code prompts/get}.
 *
 * @param description a description of the rendered prompt, or {@code null}
 * @param messages    the messages, in order
 */
public record McpPromptResult(String description, List<McpPromptMessage> messages) {

    public McpPromptResult {
        messages = List.copyOf(messages);
    }
}
