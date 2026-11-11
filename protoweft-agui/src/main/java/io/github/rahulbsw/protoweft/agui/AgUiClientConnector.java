package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.TextMessageContent;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.agent.AgentArtifact;
import io.github.rahulbsw.protoweft.core.agent.AgentConnector;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentEvent;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A remote AG-UI agent behind the protocol-neutral {@link AgentConnector}, and as an {@code ask}
 * tool for registries. Each conversation ({@code contextId}, else {@code taskId}) is an
 * {@link AgUiThread} kept in memory; its id is both the task id and the context id of replies.
 *
 * <ul>
 *   <li>A finished turn is {@code COMPLETED} with the assistant's text as the message.</li>
 *   <li>An interrupted run is {@code INPUT_REQUIRED} with the interrupts' messages. The next message
 *       on the task answers them: its text when there is one interrupt, or a JSON part mapping
 *       interrupt ids to answers.</li>
 *   <li>Calls to tools this client does not run are {@code INPUT_REQUIRED} too; the next message's
 *       text answers each of them.</li>
 *   <li>{@code RUN_ERROR} is {@code FAILED}; a cancelled run {@code CANCELED}; a stream that ended
 *       without an outcome {@code UNKNOWN}.</li>
 *   <li>Streaming: {@code WORKING} first, then the assistant's text as chunks of a {@code response}
 *       artifact, then the final status.</li>
 *   <li>{@link #getTask} is unsupported (AG-UI has no tasks); {@link #cancel} closes the stream of
 *       the run in flight, the only stop a client has.</li>
 * </ul>
 */
public final class AgUiClientConnector implements AgentConnector, ToolConnector {

    private final AgUiClient client;
    private final UnaryOperator<AgUiThread.Builder> threads;
    private final Map<String, AgUiThread> conversations = new ConcurrentHashMap<>();
    private AgentDescriptor descriptor;
    private volatile boolean started;

    private AgUiClientConnector(Builder b) {
        this.client = b.client.build();
        this.threads = b.threads;
        this.descriptor = b.descriptor;
    }

    public static Builder builder(String endpoint) {
        return new Builder(AgUiClient.builder(endpoint));
    }

    @Override
    public ProtocolId protocol() {
        return ProtocolId.AGUI;
    }

    /** Ready to use. If no name was configured, the agent's capabilities document (if any) supplies it. */
    @Override
    public synchronized void start() {
        if (descriptor == null) {
            String name = "AG-UI agent";
            String description = "";
            String version = "";
            Object identity = client.capabilities().map(c -> c.get("identity")).orElse(null);
            if (identity instanceof Map<?, ?> id) {
                name = id.get("name") instanceof String s ? s : name;
                description = id.get("description") instanceof String s ? s : description;
                version = id.get("version") instanceof String s ? s : version;
            }
            descriptor = new AgentDescriptor(name, description, version, List.of());
        }
        started = true;
    }

    @Override
    public ComplianceReport compliance() {
        return client.compliance();
    }

    @Override
    public synchronized AgentDescriptor descriptor() {
        requireStarted();
        return descriptor;
    }

    @Override
    public AgentReply send(AgentMessage message) {
        return send(message, e -> { });
    }

    @Override
    public AgentReply send(AgentMessage message, Consumer<AgentEvent> events) {
        requireStarted();
        String threadId = message.contextId() != null ? message.contextId()
                : message.taskId() != null ? message.taskId() : UUID.randomUUID().toString();
        Set<String> started = new HashSet<>();
        AgUiThread thread = conversations.computeIfAbsent(threadId, id -> threads.apply(AgUiThread.builder(client)
                .threadId(id)).build());
        events.accept(new AgentEvent.StatusUpdate(threadId, TaskState.WORKING, null));
        AgUiThread.Turn turn;
        synchronized (thread) {
            Consumer<AgUiEvent> forward = event -> {
                if (event instanceof TextMessageContent c) {
                    boolean first = started.add(c.messageId());
                    events.accept(new AgentEvent.ArtifactUpdate(threadId, new AgentArtifact(c.messageId(), "response",
                            List.of(new Content.Text(c.delta()))), !first, false));
                }
            };
            turn = next(thread, message, forward);
        }
        AgentReply reply = reply(threadId, turn);
        events.accept(new AgentEvent.StatusUpdate(threadId, reply.state(), reply.message()));
        return reply;
    }

    private AgUiThread.Turn next(AgUiThread thread, AgentMessage message, Consumer<AgUiEvent> forward) {
        List<Interrupt> open = thread.openInterrupts();
        if (!open.isEmpty()) {
            Map<String, Object> answers = answers(message, open);
            List<RunAgentInput.ResumeEntry> entries = new java.util.ArrayList<>();
            answers.forEach((id, payload) -> entries.add(new RunAgentInput.ResumeEntry(id,
                    RunAgentInput.ResumeEntry.RESOLVED, payload, null)));
            return thread.resume(entries, forward);
        }
        if (!thread.pendingCalls().isEmpty()) {
            for (AgUiThread.PendingCall call : thread.pendingCalls()) {
                thread.answer(call.toolCallId(), message.text(), null);
            }
            return thread.continueRun(forward);
        }
        return thread.send(new UserMessage(message.messageId() != null ? message.messageId()
                : UUID.randomUUID().toString(), Contents.toAgUi(message.parts()), null, null, null, null), forward);
    }

    private static Map<String, Object> answers(AgentMessage message, List<Interrupt> open) {
        for (Content part : message.parts()) {
            if (part instanceof Content.Json j && j.value() instanceof Map<?, ?> m
                    && open.stream().allMatch(i -> m.containsKey(i.id()))) {
                Map<String, Object> out = new LinkedHashMap<>();
                open.forEach(i -> out.put(i.id(), m.get(i.id())));
                return out;
            }
        }
        if (open.size() != 1) {
            throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.AGUI, "the agent is waiting on "
                    + open.size() + " interrupts; answer with a JSON part mapping each id to its answer: "
                    + open.stream().map(Interrupt::id).toList());
        }
        Object payload = message.text();
        for (Content part : message.parts()) {
            if (part instanceof Content.Json j && message.text().isEmpty()) {
                payload = j.value();
            }
        }
        return Map.of(open.get(0).id(), payload);
    }

    private static AgentReply reply(String threadId, AgUiThread.Turn turn) {
        TaskState state;
        String text = turn.text();
        switch (turn.status()) {
            case COMPLETED, STOPPED -> state = TaskState.COMPLETED;
            case INTERRUPTED -> {
                state = TaskState.INPUT_REQUIRED;
                text = turn.interrupts().stream().map(i -> i.message() != null ? i.message() : i.reason())
                        .reduce((a, b) -> a + "\n" + b).orElse("The agent is waiting for input.");
            }
            case AWAITING_TOOL_RESULTS -> {
                state = TaskState.INPUT_REQUIRED;
                text = "The agent called tools this client does not run: " + turn.pendingCalls().stream()
                        .map(c -> c.toolName() + "(" + c.arguments() + ")").toList() + ". Reply with the result.";
            }
            case FAILED -> {
                state = TaskState.FAILED;
                text = turn.lastRun().error().message();
            }
            case CANCELLED -> state = TaskState.CANCELED;
            default -> {
                state = TaskState.UNKNOWN;
                text = "The stream ended before the run's outcome.";
            }
        }
        return new AgentReply(true, threadId, threadId, state, text == null || text.isEmpty() ? null : AgentMessage.agent(text),
                List.of());
    }

    /** Unsupported: AG-UI has no task resource to read. */
    @Override
    public AgentReply getTask(String taskId) {
        throw new ProtoweftException(ErrorCategory.UNSUPPORTED, ProtocolId.AGUI,
                "AG-UI has no tasks to read; the conversation is held by this client (" + taskId + ")");
    }

    /** Closes the stream of the run in flight on this conversation. */
    @Override
    public AgentReply cancel(String taskId) {
        AgUiThread thread = conversations.get(taskId);
        if (thread == null) {
            throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.AGUI, "no conversation " + taskId);
        }
        thread.cancel();
        return new AgentReply(true, taskId, taskId, TaskState.CANCELED, null, List.of());
    }

    /** The conversation behind a task id, for AG-UI-specific access (state, messages). */
    public AgUiThread thread(String taskId) {
        return conversations.get(taskId);
    }

    @Override
    public List<ToolSpec> listTools() {
        return List.of(AskTool.spec(descriptor()));
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        requireStarted();
        return AskTool.outcome(send(AskTool.message(invocation)));
    }

    @Override
    public void close() {
        conversations.values().forEach(AgUiThread::cancel);
        conversations.clear();
        started = false;
    }

    private void requireStarted() {
        if (!started) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.AGUI, "call start() first");
        }
    }

    public static final class Builder {
        private final AgUiClient.Builder client;
        private UnaryOperator<AgUiThread.Builder> threads = UnaryOperator.identity();
        private AgentDescriptor descriptor;

        private Builder(AgUiClient.Builder client) {
            this.client = client;
        }

        /** HTTP, credential and limit settings of the underlying {@link AgUiClient}. */
        public Builder client(Consumer<AgUiClient.Builder> configure) {
            configure.accept(client);
            return this;
        }

        /**
         * Settings applied to every conversation, for example offered tools and approval:
         * {@code b -> b.tools(registry, spec -> true).approval(approval)}.
         */
        public Builder conversations(UnaryOperator<AgUiThread.Builder> configure) {
            this.threads = Objects.requireNonNull(configure, "configure");
            return this;
        }

        /** How the agent is described (the {@code ask} tool's description). Default: its capabilities' identity. */
        public Builder descriptor(AgentDescriptor descriptor) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            return this;
        }

        public AgUiClientConnector build() {
            return new AgUiClientConnector(this);
        }
    }
}
