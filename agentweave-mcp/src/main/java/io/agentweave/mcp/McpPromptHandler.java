package io.agentweave.mcp;

/**
 * Renders one prompt. It may return a {@code String} (a single user message), an
 * {@link McpPromptMessage}, a list of them, or an {@link McpPromptResult}. Throwing
 * {@link io.agentweave.core.AgentWeaveException} with {@code INVALID_ARGUMENTS} answers with
 * JSON-RPC {@code -32602}; any other failure with {@code -32603}.
 */
@FunctionalInterface
public interface McpPromptHandler {

    Object render(McpPromptRequest request) throws Exception;
}
