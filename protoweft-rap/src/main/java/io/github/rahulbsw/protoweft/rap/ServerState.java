package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A {@link RapToolServer}'s configuration and live calls, subscriptions and choices. */
final class ServerState {

    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9_-]");

    final ToolSource tools;
    final String name;
    final String description;
    final Supplier<String> baseUrl;
    final String basePath;
    final RapStore store;
    final String storeKey;
    final ExecutorService executor;
    final boolean ownsExecutor;
    final ExecutorService deliveries;
    final CallbackSender sender;
    final RapCallbackPolicy policy;
    final Duration choiceTimeout;
    final Clock clock;

    final Map<String, ServerCall> running = new ConcurrentHashMap<>();
    final Map<String, SubscriptionHandle> subscriptions = new ConcurrentHashMap<>();
    final Map<String, CompletableFuture<Integer>> choices = new ConcurrentHashMap<>();

    ServerState(ToolSource tools, String name, String description, Supplier<String> baseUrl, String basePath,
                RapStore store, String storeKey, ExecutorService executor, boolean ownsExecutor,
                ExecutorService deliveries, CallbackSender sender, RapCallbackPolicy policy, Duration choiceTimeout,
                Clock clock) {
        this.tools = tools;
        this.name = name;
        this.description = description;
        this.baseUrl = baseUrl;
        this.basePath = basePath;
        this.store = store;
        this.storeKey = storeKey;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.deliveries = deliveries;
        this.sender = sender;
        this.policy = policy;
        this.choiceTimeout = choiceTimeout;
        this.clock = clock;
    }

    static String key(String threadId, String toolCallId) {
        return threadId + "\u0000" + toolCallId;
    }

    /**
     * Published (RAP-safe) name → tool: {@code [^A-Za-z0-9_-]} becomes {@code _} (toolsets.md "Tool
     * Names"); the mapping is kept, so invocations resolve back to the source's names.
     *
     * @throws IllegalStateException if two names collide or one is longer than 128 characters
     */
    Map<String, ToolSpec> published() {
        Map<String, ToolSpec> out = new LinkedHashMap<>();
        for (ToolSpec spec : tools.listTools()) {
            String published = UNSAFE_NAME.matcher(spec.name()).replaceAll("_");
            if (published.isEmpty() || published.length() > 128) {
                throw new IllegalStateException("tool name '" + spec.name() + "' must publish as 1 to 128 characters");
            }
            ToolSpec previous = out.put(published, spec);
            if (previous != null) {
                throw new IllegalStateException("tools '" + previous.name() + "' and '" + spec.name()
                        + "' both publish as '" + published + "'");
            }
        }
        return out;
    }

    /** Stores and registers a live subscription (lifecycle.md "Subscription Tools": store, then confirm). */
    SubscriptionHandle openSubscription(RapStore.ServerSubscription record, CallbackSender.Queue queue,
                                        boolean released) {
        SubscriptionHandle handle = new SubscriptionHandle(this, record, queue, released);
        store.saveSubscription(record);
        subscriptions.put(key(record.threadId(), record.toolCallId()), handle);
        return handle;
    }

    /** Reattaches this server's stored subscriptions. */
    List<RapSubscription> recover() {
        List<RapSubscription> out = new ArrayList<>();
        for (RapStore.ServerSubscription s : store.subscriptions()) {
            if (!s.server().equals(storeKey)) {
                continue;
            }
            SubscriptionHandle handle = new SubscriptionHandle(this, s, sender.queue(URI.create(s.callbackUrl())), true);
            subscriptions.put(key(s.threadId(), s.toolCallId()), handle);
            out.add(handle);
        }
        return List.copyOf(out);
    }

    void forget(SubscriptionHandle handle) {
        RapStore.ServerSubscription r = handle.record();
        subscriptions.remove(key(r.threadId(), r.toolCallId()), handle);
        store.deleteSubscription(r.server(), r.threadId(), r.toolCallId());
    }

    void shutdown() {
        if (ownsExecutor) {
            executor.shutdown();
        }
        deliveries.shutdown();
    }
}
