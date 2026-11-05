package io.agentweave.mcp;

import io.agentweave.core.tool.ToolProgress;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Forwards a running tool's progress and log messages to the MCP session that called it.
 * Progress goes out only when the request carried a {@code progressToken}; log messages only at
 * or above the level the client set (the SDK filters them, from {@code info} by default).
 * Each notification is sent before the call returns, so it reaches the client ahead of the
 * result.
 */
final class SessionProgress implements ToolProgress {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final McpAsyncServerExchange exchange;
    private final Object progressToken;
    private final String logger;

    SessionProgress(McpAsyncServerExchange exchange, Object progressToken, String logger) {
        this.exchange = exchange;
        this.progressToken = progressToken;
        this.logger = logger;
    }

    @Override
    public void progress(double done, Double total, String message) {
        if (progressToken != null) {
            send(exchange.progressNotification(McpSchema.ProgressNotification.builder(progressToken, done)
                    .total(total).message(message).build()));
        }
    }

    @Override
    public void log(Level level, String message) {
        if (level != null && message != null) {
            send(exchange.loggingNotification(McpSchema.LoggingMessageNotification
                    .builder(McpSchema.LoggingLevel.valueOf(level.name()), message).logger(logger).build()));
        }
    }

    private static void send(Mono<Void> notification) {
        try {
            notification.block(SEND_TIMEOUT);
        } catch (RuntimeException ignored) {
            // ToolProgress never throws: a lost notification must not fail the tool.
        }
    }
}
