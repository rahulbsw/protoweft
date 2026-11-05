package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.concurrent.TimeoutException;

/** Maps MCP SDK failures onto the neutral {@link ErrorCategory} model. */
final class McpErrors {

    /** The SDK's message when the server answers {@code initialize} with an unknown revision. */
    private static final String UNSUPPORTED_VERSION = "Unsupported protocol version";

    private McpErrors() {
    }

    static AgentWeaveException translate(RuntimeException e, String operation) {
        if (e instanceof AgentWeaveException already) {
            return already;
        }
        McpHttpClientTransportAuthorizationException denied =
                findCause(e, McpHttpClientTransportAuthorizationException.class);
        if (denied != null) {
            String status = denied.getResponseInfo() == null ? null
                    : Integer.toString(denied.getResponseInfo().statusCode());
            return new AgentWeaveException(ErrorCategory.AUTHENTICATION, ProtocolId.MCP, status,
                    "MCP " + operation + " rejected by the server" + (status == null ? "" : " with HTTP " + status), e);
        }
        if (hasCause(e, TimeoutException.class)) {
            return new AgentWeaveException(ErrorCategory.TIMEOUT, ProtocolId.MCP,
                    "MCP " + operation + " timed out", e);
        }
        McpError mcpError = findCause(e, McpError.class);
        if (mcpError != null && mcpError.getJsonRpcError() != null) {
            McpSchema.JSONRPCResponse.JSONRPCError rpc = mcpError.getJsonRpcError();
            return new AgentWeaveException(categoryFor(rpc.code(), rpc.message(), operation), ProtocolId.MCP,
                    Integer.toString(rpc.code()), "MCP " + operation + " failed: " + rpc.message(), e);
        }
        ErrorCategory category = "initialize".equals(operation) ? ErrorCategory.CONNECTION : ErrorCategory.PROTOCOL;
        return new AgentWeaveException(category, ProtocolId.MCP, "MCP " + operation + " failed: " + e.getMessage(), e);
    }

    private static ErrorCategory categoryFor(int code, String message, String operation) {
        if ("initialize".equals(operation) && UNSUPPORTED_VERSION.equals(message)) {
            return ErrorCategory.VERSION_MISMATCH;
        }
        // The MCP spec reports both unknown tools and invalid arguments as -32602, so the
        // two cannot be told apart reliably; both map to INVALID_ARGUMENTS, as does an unknown resource URI.
        if (code == McpSchema.ErrorCodes.INVALID_PARAMS || code == McpSchema.ErrorCodes.RESOURCE_NOT_FOUND) {
            return ErrorCategory.INVALID_ARGUMENTS;
        }
        if (code == McpSchema.ErrorCodes.METHOD_NOT_FOUND) {
            return ErrorCategory.UNSUPPORTED;
        }
        return ErrorCategory.PROTOCOL;
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        return findCause(t, type) != null;
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
