package io.github.rahulbsw.protoweft.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wraps a client transport to see each inbound notification in arrival order, on the
 * transport's own thread, before the SDK session handles it. The SDK's sync client runs every
 * notification consumer on its own worker thread, which reorders progress and log messages;
 * tapping here keeps their order and delivers them before the response that follows them.
 */
final class NotificationTap implements McpClientTransport {

    private final McpClientTransport delegate;
    private final Consumer<McpSchema.JSONRPCNotification> tap;

    NotificationTap(McpClientTransport delegate, Consumer<McpSchema.JSONRPCNotification> tap) {
        this.delegate = delegate;
        this.tap = tap;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(inbound -> handler.apply(inbound.doOnNext(message -> {
            if (message instanceof McpSchema.JSONRPCNotification notification) {
                try {
                    tap.accept(notification);
                } catch (RuntimeException ignored) {
                    // The tap must never break the session.
                }
            }
        })));
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        delegate.setExceptionHandler(handler);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return delegate.sendMessage(message);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
