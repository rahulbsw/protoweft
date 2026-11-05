package io.agentweave.argi.neutral;

import io.agentweave.core.agent.AgentArtifact;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentTaskContext;
import io.agentweave.core.tool.Content;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Records every task call as a short string, with the calling thread. */
final class FakeTask implements AgentTaskContext {

    final List<String> events = new CopyOnWriteArrayList<>();
    final List<String> threads = new CopyOnWriteArrayList<>();
    final List<AgentArtifact> artifacts = new CopyOnWriteArrayList<>();
    private final String taskId;
    volatile boolean cancelled;
    /** Cancels the task once an event matching it is recorded. */
    volatile Predicate<String> cancelWhen = e -> false;

    FakeTask(String taskId) {
        this.taskId = taskId;
    }

    private void record(String event) {
        events.add(event);
        threads.add(Thread.currentThread().getName());
        if (cancelWhen.test(event)) {
            cancelled = true;
        }
    }

    @Override public String taskId() { return taskId; }
    @Override public String contextId() { return "ctx-1"; }
    @Override public void reply(AgentMessage message) { record("reply:" + message.text()); }
    @Override public void working(String status) { record("working:" + status); }
    @Override public void artifact(AgentArtifact artifact) {
        artifacts.add(artifact);
        record("artifact:" + artifact.name() + ":" + ((Content.Text) artifact.parts().get(0)).text());
    }
    @Override public void requireInput(String question) { record("input:" + question); }
    @Override public void requireAuth(String reason) { record("auth:" + reason); }
    @Override public void complete(String message) { record("complete"); }
    @Override public void fail(String reason) { record("fail:" + reason); }
    @Override public void reject(String reason) { record("reject:" + reason); }
    @Override public boolean isCancelled() { return cancelled; }
}
