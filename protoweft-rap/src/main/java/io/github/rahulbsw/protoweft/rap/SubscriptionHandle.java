package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.Content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link RapSubscription} over a per-call callback queue (subscription-events.md). */
final class SubscriptionHandle implements RapSubscription {

    private static final System.Logger LOG = System.getLogger(RapToolServer.class.getName());

    private final ServerState state;
    private final RapStore.ServerSubscription record;
    private final CallbackSender.Queue queue;
    private final List<Runnable> cancelHooks = new ArrayList<>();
    private final List<Map<String, Object>> held = new ArrayList<>();
    private boolean active = true;
    private boolean ended;
    private boolean released;

    SubscriptionHandle(ServerState state, RapStore.ServerSubscription record, CallbackSender.Queue queue,
                       boolean released) {
        this.state = state;
        this.record = record;
        this.queue = queue;
        this.released = released;
    }

    RapStore.ServerSubscription record() {
        return record;
    }

    @Override
    public String operation() {
        return record.operation();
    }

    @Override
    public String threadId() {
        return record.threadId();
    }

    @Override
    public String toolCallId() {
        return record.toolCallId();
    }

    @Override
    public String caller() {
        return record.caller();
    }

    @Override
    public Optional<String> userId() {
        return Optional.ofNullable(record.userId());
    }

    @Override
    public boolean emit(String text, boolean associative) {
        return send(text, associative, false);
    }

    @Override
    public boolean emit(List<Content> content, boolean associative) {
        return send(ServerResults.asText(content), associative, false);
    }

    @Override
    public boolean close(String text, boolean associative) {
        return send(text, associative, true);
    }

    @Override
    public synchronized boolean isActive() {
        return active;
    }

    @Override
    public void onCancel(Runnable action) {
        boolean runNow;
        synchronized (this) {
            runNow = ended;
            if (!runNow) {
                cancelHooks.add(action);
            }
        }
        if (runNow) {
            run(action);
        }
    }

    /** The call's result was delivered: held events may follow. */
    void release() {
        // Enqueue under the lock: an event emitted concurrently must not overtake the held ones.
        synchronized (this) {
            released = true;
            held.forEach(queue::send);
            held.clear();
        }
    }

    /** {@code /cancel_tool_call}: stop without another event (subscription-events.md "Security Considerations"). */
    void cancelled() {
        synchronized (this) {
            active = false;
            held.clear();
        }
        finish();
    }

    /** {@code /close_thread}: end with an empty final event so the runtime stops tracking it. */
    void threadClosed() {
        send("", false, true);
        finish();
    }

    private boolean send(String text, boolean associative, boolean last) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "subscription_event");
        msg.put("group_id", record.threadId());
        msg.put("tool_call_id", record.toolCallId());
        msg.put("text", text == null ? "" : text);
        msg.put("associative", associative);
        if (last) {
            msg.put("final", true);
        }
        synchronized (this) {
            if (!active) {
                return false;
            }
            if (last) {
                active = false;
            }
            if (!released) {
                held.add(msg);
            } else {
                queue.send(msg);
            }
        }
        if (last) {
            state.forget(this);
        }
        return true;
    }

    private void finish() {
        List<Runnable> hooks;
        synchronized (this) {
            if (ended) {
                return;
            }
            ended = true;
            active = false;
            hooks = new ArrayList<>(cancelHooks);
            cancelHooks.clear();
        }
        state.forget(this);
        hooks.forEach(SubscriptionHandle::run);
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "RAP subscription cancel hook failed", e);
        }
    }
}
