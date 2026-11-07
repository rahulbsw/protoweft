package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Tools exercising every RAP server feature. */
public final class TestTools {

    final BlockingQueue<RapSubscription> subscriptions = new LinkedBlockingQueue<>();
    final BlockingQueue<String> cancelled = new LinkedBlockingQueue<>();
    final CountDownLatch release = new CountDownLatch(1);

    @AgentTool(description = "Echoes the text", readOnly = true)
    public String echo(String text) {
        return text;
    }

    @AgentTool(description = "Always fails")
    public String fail() {
        throw new IllegalStateException("database down");
    }

    @AgentTool(description = "Returns an image")
    public ToolOutcome image() {
        return new ToolOutcome(List.of(new Content.Text("A pixel:"), new Content.Data("image/png", "iVBORw0KGgo=")),
                false, null);
    }

    @AgentTool(description = "Reports the caller and user")
    public String whoami(CallerIdentity caller, RapCallContext context) {
        return caller.name() + "/" + context.userId().orElse("-") + "/" + caller.attributes().get("rap.userId");
    }

    @AgentTool(description = "Subscribes to events")
    public String watch(RapCallContext context) {
        RapSubscription sub = context.subscribe();
        sub.onCancel(() -> cancelled.add(sub.toolCallId()));
        sub.emit("first");
        subscriptions.add(sub);
        return "subscribed";
    }

    @AgentTool(description = "Asks the user")
    public String ask(RapCallContext context) {
        return "chose " + context.askUser("Proceed?", List.of("yes", "no"), 1);
    }

    @AgentTool(description = "Needs authorization")
    public String login(RapCallContext context) {
        context.requestAuthorization("https://auth.example/authorize?state=s");
        return "authorized";
    }

    @AgentTool(description = "Runs until cancelled")
    public String slow(RapCallContext context) throws InterruptedException {
        CountDownLatch stop = new CountDownLatch(1);
        context.onCancel(stop::countDown);
        boolean stopped = stop.await(10, TimeUnit.SECONDS);
        cancelled.add(context.toolCallId());
        return stopped && context.isCancelled() ? "stopped" : "timed out";
    }

    @AgentTool(description = "Answers once released")
    public String hold() throws InterruptedException {
        return release.await(10, TimeUnit.SECONDS) ? "late result" : "never released";
    }

    @AgentTool(description = "Reports whether RAP features are available")
    public String probe(RapCallContext context) {
        return context.available() ? "rap" : "other";
    }
}
