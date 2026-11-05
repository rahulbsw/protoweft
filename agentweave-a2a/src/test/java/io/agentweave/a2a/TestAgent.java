package io.agentweave.a2a;

import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.agent.AgentRequest;
import io.agentweave.core.agent.AgentTaskContext;
import io.agentweave.core.tool.Content;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A scripted agent: the first word of the message selects the behavior. */
final class TestAgent implements AgentHandler {

    static final AgentDescriptor DESCRIPTOR = new AgentDescriptor("Test Agent", "Answers scripted test messages", "1.2.3",
            List.of(new AgentDescriptor.Skill("echo", "Echo", "Repeats what you say", List.of("test"), List.of("hello")),
                    new AgentDescriptor.Skill("booking", "Booking", "Books tables", List.of("booking"), List.of())));

    static final AgentDescriptor EXTENDED = new AgentDescriptor("Test Agent (extended)", "Answers scripted test messages",
            "1.2.3", List.of(new AgentDescriptor.Skill("admin", "Admin", "Privileged operations", List.of("admin"), List.of())));

    final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();
    final Set<String> sawCancelFlag = ConcurrentHashMap.newKeySet();
    final Map<String, List<String>> historyByTask = new ConcurrentHashMap<>();
    volatile CountDownLatch release = new CountDownLatch(1);

    @Override
    public void handle(AgentRequest request, AgentTaskContext task) throws Exception {
        String text = request.text().trim();
        String command = text.split("\\s+")[0];
        historyByTask.put(request.taskId(), request.history().stream().map(m -> m.text()).toList());
        if (request.resumed()) {
            task.complete("Booked for " + text);
            return;
        }
        switch (command) {
            case "hello" -> task.reply("hi there");
            case "whoami" -> task.reply(request.caller().name());
            case "report" -> {
                task.working("crunching");
                task.artifact("report", List.of(new Content.Text("line1")));
                task.artifact("data", List.of(new Content.Json(Map.of("rows", 2))));
                task.complete("done");
            }
            case "book" -> task.requireInput("For how many people?");
            case "login" -> task.requireAuth("Please sign in");
            case "slow" -> {
                task.working("started");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!task.isCancelled() && System.nanoTime() < until) {
                    Thread.sleep(20);
                }
                if (task.isCancelled()) {
                    sawCancelFlag.add(task.taskId());
                }
                task.complete("finished anyway");
            }
            case "wait" -> {
                task.working("waiting");
                release.await(20, TimeUnit.SECONDS);
                task.artifact("late", List.of(new Content.Text("released")));
                task.complete("released");
            }
            case "boom" -> throw new IllegalStateException("kaboom");
            case "reject" -> task.reject("not my job");
            case "quiet" -> task.working(null); // returns without finishing: auto-completed
            default -> task.reply("unknown: " + text);
        }
    }

    @Override
    public void onCancel(String taskId) {
        cancelledTasks.add(taskId);
    }
}
