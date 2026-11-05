package io.agentweave.mcp;

import io.agentweave.core.tool.ToolProgress;

/**
 * A log message an MCP server sent with {@code notifications/message}.
 *
 * @param level  its severity
 * @param logger the logger name the server gave, or {@code null}
 * @param data   the message
 */
public record McpLogMessage(ToolProgress.Level level, String logger, String data) {
}
