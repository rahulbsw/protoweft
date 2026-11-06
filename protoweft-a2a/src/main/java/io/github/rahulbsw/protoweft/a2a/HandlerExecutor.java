package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentArtifact;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adapts an Protoweft {@link AgentHandler} to the SDK's {@link AgentExecutor} (lifecycle rules in SPEC.md). */
final class HandlerExecutor implements AgentExecutor {

    /** {@link ServerCallContext} state key holding the Protoweft {@link CallerIdentity}. */
    static final String CALLER_KEY = "io.github.rahulbsw.protoweft.caller";

    private static final Logger LOG = LoggerFactory.getLogger(HandlerExecutor.class);

    private final AgentHandler handler;
    private final TaskOwnership ownership;
    private final ObservationHandler observations;
    private final Map<String, TaskContext> running = new ConcurrentHashMap<>();

    HandlerExecutor(AgentHandler handler, TaskOwnership ownership, ObservationHandler observations) {
        this.handler = handler;
        this.ownership = ownership;
        this.observations = observations;
    }

    static CallerIdentity caller(ServerCallContext context) {
        if (context != null && context.getState().get(CALLER_KEY) instanceof CallerIdentity c) {
            return c;
        }
        return CallerIdentity.ANONYMOUS;
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        CallerIdentity caller = caller(context.getCallContext());
        // Claim the task before the handler emits anything, so the caller can cancel or
        // subscribe while a blocking send is still running.
        ownership.claim(taskId, caller.name());
        Task existing = context.getTask();
        Message incoming = context.getMessage();
        AgentMessage message = incoming == null ? AgentMessage.user("") : A2aMapping.toNeutral(incoming);
        AgentRequest request = new AgentRequest(message, taskId, context.getContextId(), existing != null,
                history(existing, incoming), caller);
        TaskContext task = new TaskContext(taskId, context.getContextId(), emitter, existing != null, incoming);
        running.put(taskId, task);
        try {
            ObservationHandler.observe(observations, new Observation(ProtocolId.A2A, "agent/execute", taskId), () -> {
                try {
                    handler.handle(request, task);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new HandlerFailure(e);
                }
                return null;
            });
            task.finishIfOpen();
        } catch (RuntimeException e) {
            Throwable cause = e instanceof HandlerFailure ? e.getCause() : e;
            LOG.debug("agent handler failed for task {}", taskId, cause);
            task.failIfOpen(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } finally {
            running.remove(taskId, task);
        }
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        TaskContext task = running.get(taskId);
        if (task != null) {
            task.markCancelled();
        }
        try {
            handler.onCancel(taskId);
        } catch (RuntimeException e) {
            LOG.warn("onCancel hook failed for task {}", taskId, e);
        }
        emitter.cancel();
    }

    /** Earlier messages of the task, without the message being delivered now. */
    private static List<AgentMessage> history(Task task, Message incoming) {
        if (task == null || task.history() == null) {
            return List.of();
        }
        List<AgentMessage> out = new ArrayList<>();
        for (Message m : task.history()) {
            if (incoming == null || !m.messageId().equals(incoming.messageId())) {
                out.add(A2aMapping.toNeutral(m));
            }
        }
        return out;
    }

    private static final class HandlerFailure extends RuntimeException {
        HandlerFailure(Exception cause) {
            super(cause);
        }
    }

    /** One handler invocation's view of its task; forwards each call to the SDK emitter. */
    static final class TaskContext implements AgentTaskContext {

        private final String taskId;
        private final String contextId;
        private final AgentEmitter emitter;
        private final Message incoming;
        private boolean started;
        private boolean replied;
        private boolean closed;
        private volatile boolean cancelled;

        TaskContext(String taskId, String contextId, AgentEmitter emitter, boolean existing, Message incoming) {
            this.taskId = taskId;
            this.contextId = contextId;
            this.emitter = emitter;
            this.started = existing;
            this.incoming = incoming;
        }

        @Override
        public String taskId() {
            return taskId;
        }

        @Override
        public String contextId() {
            return contextId;
        }

        @Override
        public synchronized void reply(AgentMessage message) {
            if (cancelled) {
                return;
            }
            if (started || replied || closed) {
                throw new IllegalStateException("reply() must be the only call and cannot answer an existing task; "
                        + "use complete(message) instead");
            }
            replied = true;
            emitter.sendMessage(A2aMapping.toParts(message.parts()), message.metadata().isEmpty() ? null : message.metadata());
        }

        @Override
        public synchronized void working(String status) {
            if (open()) {
                emitter.startWork(agentMessage(status));
            }
        }

        @Override
        public synchronized void artifact(AgentArtifact artifact) {
            if (open()) {
                emitter.addArtifact(A2aMapping.toParts(artifact.parts()), artifact.artifactId(), artifact.name(), null);
            }
        }

        @Override
        public synchronized void requireInput(String question) {
            if (open()) {
                closed = true;
                emitter.requiresInput(agentMessage(question));
            }
        }

        @Override
        public synchronized void requireAuth(String reason) {
            if (open()) {
                closed = true;
                emitter.requiresAuth(agentMessage(reason));
            }
        }

        @Override
        public synchronized void complete(String message) {
            if (open()) {
                closed = true;
                emitter.complete(agentMessage(message));
            }
        }

        @Override
        public synchronized void fail(String reason) {
            if (open()) {
                closed = true;
                emitter.fail(agentMessage(reason));
            }
        }

        @Override
        public synchronized void reject(String reason) {
            if (open()) {
                closed = true;
                emitter.reject(agentMessage(reason));
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void markCancelled() {
            cancelled = true;
        }

        /** The handler returned: complete unless it already replied, finished, or paused. */
        synchronized void finishIfOpen() {
            if (!replied && !closed && !cancelled) {
                complete(null);
            }
        }

        synchronized void failIfOpen(String reason) {
            if (!replied && !closed && !cancelled) {
                fail(reason);
            }
        }

        /** Whether events may still be emitted; creates the task on first use. */
        private boolean open() {
            if (cancelled) {
                return false;
            }
            if (replied || closed) {
                throw new IllegalStateException("task " + taskId + " already answered, finished, or paused");
            }
            if (!started) {
                started = true;
                // A Task event (not a bare status update) so the request message is kept in
                // the task's history; the SDK's event-bus TaskManager has no initial message.
                emitter.addTask(emitter.taskBuilder()
                        .status(new TaskStatus(org.a2aproject.sdk.spec.TaskState.TASK_STATE_SUBMITTED))
                        .history(incoming == null ? List.of() : List.of(incoming))
                        .build());
            }
            return true;
        }

        private Message agentMessage(String text) {
            return text == null || text.isEmpty() ? null
                    : emitter.newAgentMessage(List.of(A2aMapping.toPart(new Content.Text(text))), null);
        }
    }
}
