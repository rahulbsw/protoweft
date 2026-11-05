package io.agentweave.mcp;

/**
 * Reads one resource, or one resource of a template. It may return:
 * <ul>
 *   <li>a {@code String}: text contents;</li>
 *   <li>a {@code byte[]}: binary contents;</li>
 *   <li>an {@link McpResourceContents} or a list of them, as is;</li>
 *   <li>anything else (a record, map or list): its JSON as text, typed {@code application/json}
 *       unless the resource declares a media type.</li>
 * </ul>
 * Throwing {@link io.agentweave.core.AgentWeaveException} with {@code INVALID_ARGUMENTS} answers
 * with JSON-RPC {@code -32602}; any other failure with {@code -32603}.
 */
@FunctionalInterface
public interface McpResourceHandler {

    Object read(McpResourceRequest request) throws Exception;
}
