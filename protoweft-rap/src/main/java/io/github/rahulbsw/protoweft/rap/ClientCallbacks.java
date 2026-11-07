package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.BufferedToolSubscription;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A connector's pending calls and the processing of their callbacks: schema checks, matching
 * against ({@code group_id}, {@code id}), de-duplication and serial processing per thread.
 */
final class ClientCallbacks implements CallbackRouter.Target {

    private static final System.Logger LOG = System.getLogger(RapClientConnector.class.getName());
    private static final String ERROR_PREFIX = "Error: ";
    /** Serial processing per {@code group_id}, across every connector in the JVM. */
    /** Striped per-thread locks: a thread id always maps to the same lock, and memory stays bounded. */
    private static final ReentrantLock[] THREAD_LOCKS = new ReentrantLock[256];

    static {
        for (int i = 0; i < THREAD_LOCKS.length; i++) {
            THREAD_LOCKS[i] = new ReentrantLock();
        }
    }

    static ReentrantLock threadLock(String threadId) {
        return THREAD_LOCKS[Math.floorMod(threadId.hashCode(), THREAD_LOCKS.length)];
    }

    /** What processing needs from the connector. */
    interface Hooks {
        void prompt(Runnable task);

        RapPromptHandler prompts();

        void answerChoice(RapCall call, String responseUrl, int selected);

        void recovered(RapRecoveredResult result);

        void cancelRemote(RapCall call);
    }

    /** One call awaiting callbacks; {@code future} is null once no caller waits (detached). */
    static final class PendingCall {
        final RapCall info;
        final String token;
        volatile CompletableFuture<ToolOutcome> future;
        volatile BufferedToolSubscription subscription;

        PendingCall(RapCall info, String token, CompletableFuture<ToolOutcome> future) {
            this.info = info;
            this.token = token;
            this.future = future;
        }
    }

    private final String owner;
    private final RapStore store;
    private final CallbackRouter router;
    private final Hooks hooks;
    private final Clock clock;
    private final Duration retention;
    private final Map<String, PendingCall> calls = new ConcurrentHashMap<>();
    private final Map<String, Instant> closed = new ConcurrentHashMap<>();

    ClientCallbacks(String owner, RapStore store, CallbackRouter router, Hooks hooks, Clock clock, Duration retention) {
        this.owner = owner;
        this.store = store;
        this.router = router;
        this.hooks = hooks;
        this.clock = clock;
        this.retention = retention;
    }

    PendingCall open(RapCall info, String token, CompletableFuture<ToolOutcome> future) {
        purge();
        PendingCall call = new PendingCall(info, token, future);
        store.saveCall(new RapStore.ClientCall(owner, token, info.serverUrl(), info.threadId(), info.toolCallId(),
                info.operation(), clock.instant(), false));
        calls.put(token, call);
        router.register(token, this);
        return call;
    }

    /** Reattaches this owner's stored calls as detached calls; returns the confirmed subscriptions. */
    List<RapRecoveredSubscription> recover() {
        List<RapRecoveredSubscription> recovered = new ArrayList<>();
        for (RapStore.ClientCall c : store.calls()) {
            if (!c.owner().equals(owner) || calls.containsKey(c.token())) {
                continue;
            }
            RapCall info = new RapCall(c.operation(), c.toolCallId(), c.threadId(), c.serverUrl());
            PendingCall call = new PendingCall(info, c.token(), null);
            if (c.subscription()) {
                call.subscription = newSubscription(call);
                recovered.add(new RapRecoveredSubscription(info, call.subscription));
            }
            calls.put(c.token(), call);
            router.register(c.token(), this);
        }
        return List.copyOf(recovered);
    }

    /** Ends a call locally: later callbacks for it are acknowledged and discarded. */
    void close(PendingCall call) {
        if (calls.remove(call.token) != null) {
            closed.put(call.token, clock.instant());
            store.deleteCall(call.token);
        }
    }

    /** Detaches the waiting caller; a later result goes to the recovery listener. */
    static void detach(PendingCall call) {
        call.future = null;
    }

    /** Fails every waiting caller and stops routing (stored calls stay, for the next instance). */
    void shutdown(RuntimeException reason) {
        for (PendingCall call : calls.values()) {
            router.unregister(call.token);
            CompletableFuture<ToolOutcome> f = call.future;
            if (f != null) {
                f.completeExceptionally(reason);
            }
            if (call.subscription != null) {
                call.subscription.fail(reason);
            }
        }
        calls.clear();
        closed.keySet().forEach(router::unregister);
        closed.clear();
    }

    @Override
    public int handle(String token, byte[] body) {
        PendingCall call = calls.get(token);
        if (call == null) {
            return closed.containsKey(token) ? 200 : 404;
        }
        Map<String, Object> msg;
        String type;
        try {
            msg = Json.parseObject(body);
            type = Json.requireString(msg, "type");
            String group = Json.requireString(msg, "group_id");
            String id = Json.requireString(msg, type.equals("subscription_event") ? "tool_call_id"
                    : type.equals("view_update") ? "group_id" : "id");
            validate(type, msg);
            if (!group.equals(call.info.threadId()) || !type.equals("view_update") && !id.equals(call.info.toolCallId())) {
                LOG.log(System.Logger.Level.WARNING, "discarding RAP " + type + " that does not match its pending call");
                return 404;
            }
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "rejecting malformed RAP callback: " + e.getMessage());
            return 400;
        }
        List<Runnable> after = new ArrayList<>();
        ReentrantLock lock = threadLock(call.info.threadId());
        int status;
        lock.lock();
        try {
            status = calls.get(token) != call ? 200 : process(call, type, msg, after);
        } finally {
            lock.unlock();
        }
        after.forEach(Runnable::run);
        return status;
    }

    private int process(PendingCall call, String type, Map<String, Object> msg, List<Runnable> after) {
        switch (type) {
            case "tool_result" -> {
                if (call.subscription != null || !store.markProcessed(key(call, type, null), clock.instant())) {
                    return 200;
                }
                ToolOutcome outcome = outcome(msg);
                if (Json.optionalBoolean(msg, "subscription")) {
                    call.subscription = newSubscription(call);
                    store.saveCall(storedCall(call).asSubscription());
                    outcome = outcome.withSubscription(call.subscription);
                } else {
                    close(call);
                }
                ToolOutcome result = outcome;
                CompletableFuture<ToolOutcome> waiting = call.future;
                detach(call);
                after.add(waiting != null ? () -> waiting.complete(result)
                        : () -> hooks.recovered(new RapRecoveredResult(call.info, result)));
                return 200;
            }
            case "subscription_event" -> {
                BufferedToolSubscription sub = call.subscription;
                if (sub == null) {
                    LOG.log(System.Logger.Level.WARNING, "discarding RAP subscription_event for a call that is not a subscription");
                    return 404;
                }
                boolean last = Json.optionalBoolean(msg, "final");
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("associative", Json.optionalBoolean(msg, "associative"));
                meta.put("final", last);
                sub.emit(new ToolEvent(List.of(new Content.Text((String) msg.get("text"))), last, meta));
                if (last) {
                    close(call);
                }
                return 200;
            }
            case "oauth" -> {
                if (!store.markProcessed(key(call, type, msg), clock.instant())) {
                    return 200;
                }
                String url = (String) msg.get("auth_url");
                RapPromptHandler handler = hooks.prompts();
                CompletableFuture<ToolOutcome> waiting = call.future;
                if (handler != null) {
                    hooks.prompt(() -> handler.onAuthorization(url, call.info));
                } else if (waiting != null) {
                    detach(call);
                    after.add(() -> waiting.completeExceptionally(new RapAuthorizationRequiredException(call.info, url)));
                } else {
                    LOG.log(System.Logger.Level.WARNING, "RAP tool '" + call.info.operation()
                            + "' requires authorization and no prompt handler is configured");
                }
                return 200;
            }
            case "user_choice" -> {
                if (!store.markProcessed(key(call, type, msg), clock.instant())) {
                    return 200;
                }
                choose(call, msg);
                return 200;
            }
            default -> {
                LOG.log(System.Logger.Level.DEBUG, "ignoring RAP view_update (unsupported)");
                return 200;
            }
        }
    }

    private void choose(PendingCall call, Map<String, Object> msg) {
        String prompt = (String) msg.get("prompt");
        List<String> choices = new ArrayList<>();
        Json.array(msg.get("choices")).forEach(c -> choices.add((String) c));
        int defaultIndex = Json.requireIndex(msg, "default");
        String responseUrl = (String) msg.get("response_url");
        RapPromptHandler handler = hooks.prompts();
        hooks.prompt(() -> {
            int selected = defaultIndex;
            if (handler != null) {
                try {
                    int chosen = handler.onChoice(prompt, List.copyOf(choices), defaultIndex, call.info);
                    selected = chosen >= 0 && chosen < choices.size() ? chosen : defaultIndex;
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "prompt handler failed; sending the default choice", e);
                }
            }
            hooks.answerChoice(call.info, responseUrl, selected);
        });
    }

    private BufferedToolSubscription newSubscription(PendingCall call) {
        return new BufferedToolSubscription(call.info.toolCallId(), () -> {
            close(call);
            hooks.cancelRemote(call.info);
        });
    }

    private RapStore.ClientCall storedCall(PendingCall call) {
        return new RapStore.ClientCall(owner, call.token, call.info.serverUrl(), call.info.threadId(),
                call.info.toolCallId(), call.info.operation(), clock.instant(), false);
    }

    /** {@code text} or {@code content} (which supersedes it); "Error: " marks a failure (tool-result.md). */
    static ToolOutcome outcome(Map<String, Object> msg) {
        List<Content> parts = new ArrayList<>();
        List<Object> content = Json.array(msg.get("content"));
        if (content != null) {
            for (Object o : content) {
                Map<String, Object> item = Json.object(o);
                String itemType = (String) item.get("type");
                if (itemType.equals("text")) {
                    parts.add(new Content.Text((String) item.get("text")));
                } else if (itemType.equals("image")) {
                    parts.add(new Content.Data((String) item.get("mediaType"), (String) item.get("data")));
                } else {
                    parts.add(new Content.Unmapped(itemType));
                }
            }
        } else {
            parts.add(new Content.Text((String) msg.get("text")));
        }
        ToolOutcome outcome = new ToolOutcome(parts, false, null);
        return outcome.text().startsWith(ERROR_PREFIX) ? new ToolOutcome(parts, true, null) : outcome;
    }

    /** The message schema (transport.md "Security Considerations": runtimes MUST validate). */
    private static void validate(String type, Map<String, Object> msg) {
        switch (type) {
            case "tool_result" -> {
                Json.optionalString(msg, "call_id");
                Json.optionalBoolean(msg, "subscription");
                Object content = msg.get("content");
                if (content != null) {
                    List<Object> items = Json.array(content);
                    if (items == null) {
                        throw new IllegalArgumentException("'content' must be an array");
                    }
                    for (Object o : items) {
                        Map<String, Object> item = Json.object(o);
                        if (item == null) {
                            throw new IllegalArgumentException("content items must be objects");
                        }
                        String itemType = Json.requireString(item, "type");
                        if (itemType.equals("text")) {
                            Json.requireText(item, "text");
                        } else if (itemType.equals("image")) {
                            Json.requireString(item, "data");
                            Json.requireString(item, "mediaType");
                        }
                    }
                } else {
                    Json.requireText(msg, "text");
                }
            }
            case "subscription_event" -> {
                Json.requireText(msg, "text");
                Json.optionalBoolean(msg, "associative");
                Json.optionalBoolean(msg, "final");
            }
            case "oauth" -> {
                Json.optionalString(msg, "call_id");
                if (!Json.requireString(msg, "auth_url").regionMatches(true, 0, "https://", 0, 8)) {
                    throw new IllegalArgumentException("'auth_url' must use https");
                }
            }
            case "user_choice" -> {
                Json.optionalString(msg, "call_id");
                Json.requireText(msg, "prompt");
                List<Object> choices = Json.array(msg.get("choices"));
                if (choices == null || choices.isEmpty() || !choices.stream().allMatch(String.class::isInstance)) {
                    throw new IllegalArgumentException("'choices' must be a non-empty array of strings");
                }
                if (Json.requireIndex(msg, "default") >= choices.size()) {
                    throw new IllegalArgumentException("'default' must be a valid index into 'choices'");
                }
                Toolset.httpUrl(Json.requireString(msg, "response_url"), "response_url");
            }
            case "view_update" -> {
                Json.requireString(msg, "view_type");
                if (Json.object(msg.get("content")) == null) {
                    throw new IllegalArgumentException("'content' must be an object");
                }
            }
            default -> throw new IllegalArgumentException("unknown callback type: " + type);
        }
    }

    /** De-duplication key: message type and id, plus a digest for prompts so a new prompt is not mistaken for a retry. */
    private static String key(PendingCall call, String type, Map<String, Object> msg) {
        String base = call.token + "|" + type + "|" + call.info.toolCallId();
        if (msg == null) {
            return base;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Json.write(msg).getBytes(StandardCharsets.UTF_8));
            return base + "|" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void purge() {
        Instant cutoff = clock.instant().minus(retention);
        closed.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                router.unregister(e.getKey());
                return true;
            }
            return false;
        });
        store.purgeProcessed(cutoff);
    }
}
