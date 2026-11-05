package io.agentweave.a2a;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.agent.AgentEvent;
import io.agentweave.core.agent.AgentReply;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Turns one SDK event stream into neutral {@link AgentEvent}s and a final {@link AgentReply},
 * completed when the task is terminal or interrupted (or the stream ends).
 */
final class StreamCollector {

    final CompletableFuture<AgentReply> done = new CompletableFuture<>();
    private final AtomicReference<Task> last = new AtomicReference<>();
    private final Consumer<AgentEvent> events;
    private final String operation;

    StreamCollector(String operation, Consumer<AgentEvent> events) {
        this.operation = operation;
        this.events = events;
    }

    static boolean settled(Task task) {
        return task.status().state().isFinal() || task.status().state().isInterrupted();
    }

    BiConsumer<ClientEvent, AgentCard> consumer() {
        return (event, card) -> {
            if (done.isDone()) {
                return;
            }
            if (event instanceof MessageEvent m) {
                deliver(new AgentEvent.MessageReceived(m.getMessage().taskId(), A2aMapping.toNeutral(m.getMessage())));
                done.complete(A2aMapping.toReply(m.getMessage()));
                return;
            }
            Task task = event instanceof TaskEvent t ? t.getTask() : ((TaskUpdateEvent) event).getTask();
            last.set(task);
            if (event instanceof TaskUpdateEvent u && u.getUpdateEvent() instanceof TaskArtifactUpdateEvent a) {
                deliver(new AgentEvent.ArtifactUpdate(a.taskId(), A2aMapping.toNeutral(a.artifact()),
                        Boolean.TRUE.equals(a.append()), Boolean.TRUE.equals(a.lastChunk())));
            } else if (event instanceof TaskUpdateEvent u && u.getUpdateEvent() instanceof TaskStatusUpdateEvent s) {
                deliver(new AgentEvent.StatusUpdate(s.taskId(), A2aMapping.toNeutral(s.status().state()),
                        A2aMapping.toNeutral(s.status().message())));
            } else {
                deliver(new AgentEvent.StatusUpdate(task.id(), A2aMapping.toNeutral(task.status().state()),
                        A2aMapping.toNeutral(task.status().message())));
            }
            if (settled(task)) {
                done.complete(A2aMapping.toReply(task));
            }
        };
    }

    /** The SDK reports normal stream completion as {@code null}. */
    Consumer<Throwable> onEnd() {
        return error -> {
            if (error != null) {
                done.completeExceptionally(error);
            } else if (last.get() != null) {
                done.complete(A2aMapping.toReply(last.get()));
            } else {
                done.completeExceptionally(new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.A2A,
                        operation + " stream ended without a result"));
            }
        };
    }

    private void deliver(AgentEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException ignored) {
            // A caller's event consumer must not break the SDK's delivery thread.
        }
    }
}
