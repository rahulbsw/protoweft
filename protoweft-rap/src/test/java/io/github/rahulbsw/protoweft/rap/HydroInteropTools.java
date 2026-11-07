package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The tools {@link HydroRuntimeServeInteropTest} serves to Hydro's Rust runtime; they record what reached them. */
public final class HydroInteropTools {

    /** A 1×1 red PNG. */
    static final String RED_PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    final Set<String> invoked = ConcurrentHashMap.newKeySet();
    final Set<String> userIds = ConcurrentHashMap.newKeySet();
    final Set<String> choices = ConcurrentHashMap.newKeySet();
    /** "thread/toolCallId" of subscriptions whose onCancel ran (runtime cancel or thread closure). */
    final Set<String> ended = ConcurrentHashMap.newKeySet();
    final Set<String> cancelledCalls = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "interop-tools");
        t.setDaemon(true);
        return t;
    });

    private void seen(String tool, RapCallContext context) {
        invoked.add(tool);
        if (context != null) {
            context.userId().ifPresent(userIds::add);
        }
    }

    @AgentTool(description = "Echoes the text", readOnly = true)
    public String echo(String text) {
        invoked.add("echo");
        return text;
    }

    @AgentTool(description = "Returns text and an image")
    public ToolOutcome image() {
        invoked.add("image");
        return new ToolOutcome(List.of(new Content.Text("A red pixel:"), new Content.Data("image/png", RED_PIXEL)),
                false, null);
    }

    @AgentTool(description = "Always fails")
    public String fail() {
        invoked.add("fail");
        throw new IllegalStateException("database down");
    }

    @AgentTool(description = "Reports user_id and thread_ancestors")
    public String whoami(RapCallContext context) {
        seen("whoami", context);
        return context.userId().orElse("-") + "|" + String.join(",", context.threadAncestors());
    }

    @AgentTool(description = "Emits count events (the second associative), then a final event")
    public String ticker(int count, RapCallContext context) {
        seen("ticker", context);
        RapSubscription sub = context.subscribe();
        for (int i = 1; i <= count; i++) {
            int n = i;
            timer.schedule(() -> sub.emit("tick " + n, n == 2), 150L * n, TimeUnit.MILLISECONDS);
        }
        timer.schedule(() -> sub.close("done", false), 150L * (count + 1), TimeUnit.MILLISECONDS);
        return "ticking";
    }

    @AgentTool(description = "Asks the user to confirm a deployment")
    public String confirm(RapCallContext context) {
        seen("confirm", context);
        List<String> options = List.of("yes", "no");
        String chosen = options.get(context.askUser("Deploy to production?", options, 1, Duration.ofSeconds(60)));
        choices.add(chosen);
        return "chose " + chosen;
    }

    @AgentTool(description = "Needs OAuth authorization first")
    public String login(RapCallContext context) {
        seen("login", context);
        context.requestAuthorization("https://auth.example/authorize?state=interop");
        return "authorized";
    }

    @AgentTool(description = "Subscribes and sends a heartbeat every 300 ms until cancelled or the thread closes")
    public String watch(RapCallContext context) {
        seen("watch", context);
        RapSubscription sub = context.subscribe();
        AtomicInteger n = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> beat = new AtomicReference<>();
        sub.onCancel(() -> {
            ended.add(sub.threadId() + "/" + sub.toolCallId());
            ScheduledFuture<?> f = beat.get();
            if (f != null) {
                f.cancel(false);
            }
        });
        sub.emit("watching");
        beat.set(timer.scheduleAtFixedRate(() -> sub.emit("heartbeat " + n.incrementAndGet()),
                300, 300, TimeUnit.MILLISECONDS));
        return "watching";
    }

    @AgentTool(description = "Runs until the runtime cancels it (at most 20 s)")
    public String slow(RapCallContext context) throws InterruptedException {
        seen("slow", context);
        CountDownLatch stop = new CountDownLatch(1);
        context.onCancel(stop::countDown);
        boolean stopped = stop.await(20, TimeUnit.SECONDS);
        if (stopped && context.isCancelled()) {
            cancelledCalls.add(context.threadId() + "/" + context.toolCallId());
            return "stopped";
        }
        return "timed out";
    }

    void shutdown() {
        timer.shutdownNow();
    }
}
