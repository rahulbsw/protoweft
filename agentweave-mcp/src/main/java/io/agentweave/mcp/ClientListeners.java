package io.agentweave.mcp;

import io.agentweave.core.tool.ToolProgress;
import io.agentweave.core.tool.ToolSpec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Routes the notifications an {@link McpClientConnector} receives to the listeners registered
 * on it: list changes, resource updates (by URI), log messages, and progress (by token).
 * Log and progress listeners run on the transport's thread, in arrival order, so they must be
 * quick. Listener failures are dropped so one listener can't break notification delivery.
 */
final class ClientListeners {

    private final List<Consumer<List<ToolSpec>>> tools = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<McpResourceInfo>>> resources = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<McpPromptInfo>>> prompts = new CopyOnWriteArrayList<>();
    private final List<Consumer<McpLogMessage>> logs = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<List<McpResourceContents>>> subscriptions = new ConcurrentHashMap<>();
    private final Map<Object, ToolProgress> progress = new ConcurrentHashMap<>();

    /** Wraps {@code transport} so progress and log messages reach these listeners in order. */
    McpClientTransport tap(McpClientTransport transport) {
        return new NotificationTap(transport, n -> {
            if (McpSchema.METHOD_NOTIFICATION_PROGRESS.equals(n.method())) {
                progressed(transport.unmarshalFrom(n.params(), new TypeRef<McpSchema.ProgressNotification>() {
                }));
            } else if (McpSchema.METHOD_NOTIFICATION_MESSAGE.equals(n.method())) {
                McpSchema.LoggingMessageNotification m = transport.unmarshalFrom(n.params(),
                        new TypeRef<McpSchema.LoggingMessageNotification>() {
                        });
                dispatch(logs, new McpLogMessage(ToolProgress.Level.valueOf(m.level().name()), m.logger(), m.data()));
            }
        });
    }

    /** Registers the SDK consumers that feed the list-change and resource-update listeners. */
    void install(McpClient.SyncSpec spec) {
        spec.toolsChangeConsumer(list -> dispatch(tools, list.stream().map(McpContent::toToolSpec).toList()))
                .resourcesChangeConsumer(list -> dispatch(resources, list.stream()
                        .map(ClientListeners::toInfo).toList()))
                .promptsChangeConsumer(list -> dispatch(prompts, list.stream().map(ClientListeners::toInfo).toList()))
                .resourcesUpdateConsumer(this::updated);
    }

    void onToolsChanged(Consumer<List<ToolSpec>> listener) {
        tools.add(listener);
    }

    void onResourcesChanged(Consumer<List<McpResourceInfo>> listener) {
        resources.add(listener);
    }

    void onPromptsChanged(Consumer<List<McpPromptInfo>> listener) {
        prompts.add(listener);
    }

    void onLog(Consumer<McpLogMessage> listener) {
        logs.add(listener);
    }

    void subscribe(String uri, Consumer<List<McpResourceContents>> listener) {
        subscriptions.put(uri, listener);
    }

    void unsubscribe(String uri) {
        subscriptions.remove(uri);
    }

    void track(Object token, ToolProgress sink) {
        progress.put(token, sink);
    }

    void untrack(Object token) {
        progress.remove(token);
    }

    /** The SDK re-reads an updated resource and hands over its contents; they carry its URI. */
    private void updated(List<McpSchema.ResourceContents> contents) {
        List<McpResourceContents> neutral = contents.stream().map(McpContent::toNeutral).toList();
        neutral.stream().map(McpResourceContents::uri).distinct().forEach(uri -> {
            Consumer<List<McpResourceContents>> listener = subscriptions.get(uri);
            if (listener != null) {
                safely(() -> listener.accept(neutral));
            }
        });
    }

    private void progressed(McpSchema.ProgressNotification n) {
        ToolProgress sink = progress.get(n.progressToken());
        if (sink != null) {
            safely(() -> sink.progress(n.progress(), n.total(), n.message()));
        }
    }

    static McpResourceInfo toInfo(McpSchema.Resource r) {
        return new McpResourceInfo(r.uri(), r.name(), r.title(), r.description(), r.mimeType());
    }

    static McpTemplateInfo toInfo(McpSchema.ResourceTemplate t) {
        return new McpTemplateInfo(t.uriTemplate(), t.name(), t.title(), t.description(), t.mimeType());
    }

    static McpPromptInfo toInfo(McpSchema.Prompt p) {
        List<McpPromptArgument> args = p.arguments() == null ? List.of() : p.arguments().stream()
                .map(a -> new McpPromptArgument(a.name(), a.title(), a.description(), Boolean.TRUE.equals(a.required())))
                .toList();
        return new McpPromptInfo(p.name(), p.title(), p.description(), args);
    }

    private static <T> void dispatch(List<Consumer<T>> listeners, T value) {
        for (Consumer<T> listener : listeners) {
            safely(() -> listener.accept(value));
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // A failing listener must not stop the others or the client's notification loop.
        }
    }
}
