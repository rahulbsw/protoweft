package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.TextMessageContent;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.TextMessageStart;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ToolMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.agui.RunAgentInput.ResumeEntry;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One conversation with a remote AG-UI agent. Keeps what the protocol leaves to the client: the
 * messages, the shared state, open interrupts and unanswered tool calls, and sends them back on
 * every run.
 *
 * <pre>{@code
 * AgUiThread thread = AgUiThread.builder(client)
 *         .tools(registry, spec -> spec.name().startsWith("weather."))   // offered to the agent
 *         .approval(call -> ui.confirm(call))                            // for tools that change things
 *         .build();
 * AgUiThread.Turn turn = thread.send("Will it rain in Oslo?");
 * }</pre>
 *
 * <p>A turn may take several runs: when the agent leaves calls to offered tools pending, the thread
 * runs them (subject to {@link ToolApproval}) and continues on its own. Calls to the application's
 * other tools go to its {@link ClientToolHandler}; an unanswered one stops the turn
 * ({@link Status#AWAITING_TOOL_RESULTS}) until {@link #answer} and {@link #continueRun()}. An
 * interrupted run stops it too ({@link Status#INTERRUPTED}) until {@link #resume}. Thread-safe;
 * one run at a time.
 */
public final class AgUiThread {

    public enum Status {
        /** The agent finished and nothing is pending. */
        COMPLETED,
        /** The agent is waiting for answers to its interrupts: call {@link #resume}. */
        INTERRUPTED,
        /** Tool calls only the application can answer are pending: {@link #answer}, then {@link #continueRun()}. */
        AWAITING_TOOL_RESULTS,
        /** The agent reported a failure ({@code RUN_ERROR}). */
        FAILED,
        /** The agent stopped the run on purpose. */
        CANCELLED,
        /** The stream ended without an outcome (or this client cancelled it). */
        TRUNCATED,
        /** The automatic-run limit was reached with work left; {@link #continueRun()} goes on. */
        STOPPED
    }

    /** A tool call the agent left for the application. */
    public record PendingCall(String toolCallId, String toolName, String arguments) {
    }

    /** Answers the application's own tools; empty leaves a call pending. */
    @FunctionalInterface
    public interface ClientToolHandler {
        /** @return the result content, a {@code String} or a {@code List<ContentPart>}, or empty */
        Optional<Object> handle(PendingCall call);
    }

    /**
     * What one {@link #send}, {@link #resume} or {@link #continueRun} produced.
     *
     * @param text the assistant text streamed during the turn, across its runs
     */
    public record Turn(Status status, String text, List<Interrupt> interrupts, List<PendingCall> pendingCalls,
                       RunResult lastRun) {
    }

    private final AgUiClient client;
    private final String threadId;
    private final ToolBridge bridge;
    private final List<RunAgentInput.Tool> clientTools;
    private final ClientToolHandler clientToolHandler;
    private final List<RunAgentInput.Context> context;
    private final Object forwardedProps;
    private final Consumer<AgUiEvent> listener;
    private final int maxAutomaticRuns;
    private final Transcript transcript;
    private List<Interrupt> interrupts = List.of();
    private final Map<String, PendingCall> pending = new LinkedHashMap<>();
    private volatile AgUiClient.Call current;

    private AgUiThread(Builder b) {
        this.client = b.client;
        this.threadId = b.threadId;
        this.bridge = b.tools == null ? null : new ToolBridge(b.tools, b.toolFilter, b.approval, b.caller);
        this.clientTools = List.copyOf(b.clientTools);
        this.clientToolHandler = b.clientToolHandler;
        this.context = List.copyOf(b.context);
        this.forwardedProps = b.forwardedProps;
        this.listener = b.listener;
        this.maxAutomaticRuns = b.maxAutomaticRuns;
        this.transcript = new Transcript(b.messages, b.state, b.client.warningSink());
        if (bridge != null) {
            Set<String> names = new HashSet<>();
            bridge.tools().forEach(t -> names.add(t.name()));
            clientTools.forEach(t -> {
                if (!names.add(t.name())) {
                    throw new IllegalArgumentException("tool name '" + t.name() + "' is both bridged and an application tool");
                }
            });
        }
    }

    public static Builder builder(AgUiClient client) {
        return new Builder(client);
    }

    public String threadId() {
        return threadId;
    }

    public synchronized List<AgUiMessage> messages() {
        return transcript.messages();
    }

    public synchronized Object state() {
        return transcript.state();
    }

    public synchronized List<Interrupt> openInterrupts() {
        return interrupts;
    }

    public synchronized List<PendingCall> pendingCalls() {
        return List.copyOf(pending.values());
    }

    /** Sends a user message and runs the agent. */
    public Turn send(String text) {
        return send(UserMessage.text(UUID.randomUUID().toString(), Objects.requireNonNull(text, "text")));
    }

    public Turn send(UserMessage message) {
        return send(message, e -> { });
    }

    /** As {@link #send(UserMessage)}, with {@code events} also seeing this turn's events. */
    synchronized Turn send(UserMessage message, Consumer<AgUiEvent> events) {
        if (!interrupts.isEmpty()) {
            throw new IllegalStateException("the agent is waiting on interrupts " + ids(interrupts)
                    + "; answer or abandon them with resume() first");
        }
        requireAnswered();
        transcript.append(message);
        return runTurn(List.of(), events);
    }

    /** Resolves every open interrupt with its answer ({@code answers}: interrupt id → payload). */
    public Turn resume(Map<String, Object> answers) {
        List<ResumeEntry> entries = new ArrayList<>();
        answers.forEach((id, payload) -> entries.add(new ResumeEntry(id, ResumeEntry.RESOLVED, payload, null)));
        return resume(entries);
    }

    /**
     * Continues an interrupted run. The entries must cover every open interrupt exactly (answered,
     * or abandoned with status {@code cancelled}); an expired interrupt can only be abandoned.
     * Checked before anything is sent.
     */
    public Turn resume(List<ResumeEntry> entries) {
        return resume(entries, e -> { });
    }

    synchronized Turn resume(List<ResumeEntry> entries, Consumer<AgUiEvent> events) {
        if (interrupts.isEmpty()) {
            throw new IllegalStateException("no interrupt is open");
        }
        Set<String> open = new HashSet<>(ids(interrupts));
        Set<String> given = new HashSet<>();
        for (ResumeEntry e : entries) {
            if (!open.contains(e.interruptId())) {
                throw new IllegalArgumentException("no open interrupt '" + e.interruptId() + "'");
            }
            if (!given.add(e.interruptId())) {
                throw new IllegalArgumentException("interrupt '" + e.interruptId() + "' is answered twice");
            }
        }
        if (!given.equals(open)) {
            Set<String> missing = new HashSet<>(open);
            missing.removeAll(given);
            throw new IllegalArgumentException("every open interrupt needs an entry; missing " + missing);
        }
        for (Interrupt i : interrupts) {
            ResumeEntry e = entries.stream().filter(x -> x.interruptId().equals(i.id())).findFirst().orElseThrow();
            if (ResumeEntry.RESOLVED.equals(e.status()) && expired(i)) {
                throw new IllegalArgumentException("interrupt '" + i.id() + "' expired at " + i.expiresAt()
                        + "; it can only be abandoned");
            }
        }
        requireAnswered();
        interrupts = List.of();
        return runTurn(List.copyOf(entries), events);
    }

    /** Answers a pending call to one of the application's tools; {@code error} marks a failure or a decline. */
    public synchronized void answer(String toolCallId, Object content, String error) {
        if (pending.remove(toolCallId) == null) {
            throw new IllegalArgumentException("no pending tool call '" + toolCallId + "'");
        }
        transcript.append(new ToolMessage(UUID.randomUUID().toString(), content, toolCallId, error, null, null, null));
    }

    /** Continues after the pending calls were answered (or after {@link Status#STOPPED}). */
    public Turn continueRun() {
        return continueRun(e -> { });
    }

    synchronized Turn continueRun(Consumer<AgUiEvent> events) {
        if (!interrupts.isEmpty()) {
            throw new IllegalStateException("answer the open interrupts with resume() first");
        }
        requireAnswered();
        return runTurn(List.of(), events);
    }

    /** Closes the stream of the run in flight; the turn ends {@link Status#TRUNCATED}. */
    public void cancel() {
        AgUiClient.Call call = current;
        if (call != null) {
            call.cancel();
        }
    }

    private void requireAnswered() {
        if (!pending.isEmpty()) {
            throw new IllegalStateException("tool calls " + pending.keySet() + " are unanswered; answer() them first");
        }
    }

    private Turn runTurn(List<ResumeEntry> resume, Consumer<AgUiEvent> events) {
        StringBuilder text = new StringBuilder();
        Set<String> assistantMessages = new HashSet<>();
        List<ResumeEntry> entries = resume;
        RunResult result = null;
        for (int run = 0; run < maxAutomaticRuns; run++) {
            RunAgentInput input = new RunAgentInput(threadId, UUID.randomUUID().toString(), AgUiProtocol.VERSION,
                    null, transcript.state(), transcript.messages(), tools(), context, forwardedProps, entries);
            entries = List.of();
            AgUiClient.Call call = client.call(input);
            current = call;
            try {
                result = call.execute(event -> {
                    transcript.apply(event);
                    if (event instanceof TextMessageStart s && (s.role() == null || s.role().equals("assistant"))) {
                        assistantMessages.add(s.messageId());
                    } else if (event instanceof TextMessageContent c && assistantMessages.contains(c.messageId())) {
                        text.append(c.delta());
                    }
                    listener.accept(event);
                    events.accept(event);
                });
            } finally {
                current = null;
            }
            if (result.truncated()) {
                return turn(Status.TRUNCATED, text, result);
            }
            if (result.error() != null) {
                return turn(Status.FAILED, text, result);
            }
            if (result.cancelled()) {
                return turn(Status.CANCELLED, text, result);
            }
            interrupts = result.interrupts();
            for (String id : result.pendingToolCalls()) {
                String[] made = transcript.call(id);
                pending.put(id, new PendingCall(id, made == null ? null : made[0], made == null ? "" : made[1]));
            }
            answerPending();
            if (!interrupts.isEmpty()) {
                return turn(Status.INTERRUPTED, text, result);
            }
            if (!pending.isEmpty()) {
                return turn(Status.AWAITING_TOOL_RESULTS, text, result);
            }
            if (result.pendingToolCalls().isEmpty()) {
                return turn(Status.COMPLETED, text, result);
            }
            // Every pending call was answered here: the agent continues with the results.
        }
        return turn(Status.STOPPED, text, result);
    }

    /** Runs bridged calls and asks the application's handler about the rest. */
    private void answerPending() {
        for (PendingCall call : List.copyOf(pending.values())) {
            if (bridge != null && call.toolName() != null && bridge.offers(call.toolName())) {
                transcript.append(bridge.run(call.toolCallId(), call.toolName(), call.arguments()));
                pending.remove(call.toolCallId());
            } else if (clientToolHandler != null) {
                Optional<Object> answer = clientToolHandler.handle(call);
                if (answer.isPresent()) {
                    answer(call.toolCallId(), answer.get(), null);
                }
            }
        }
    }

    private List<RunAgentInput.Tool> tools() {
        List<RunAgentInput.Tool> tools = new ArrayList<>(clientTools);
        if (bridge != null) {
            tools.addAll(bridge.tools());
        }
        return tools;
    }

    private Turn turn(Status status, StringBuilder text, RunResult result) {
        return new Turn(status, text.toString(), interrupts, List.copyOf(pending.values()), result);
    }

    private static List<String> ids(List<Interrupt> interrupts) {
        return interrupts.stream().map(Interrupt::id).toList();
    }

    /** As the reference consumer reads it: an ISO 8601 instant at or before now. */
    private static boolean expired(Interrupt i) {
        if (i.expiresAt() == null) {
            return false;
        }
        try {
            return !Instant.parse(i.expiresAt()).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static final class Builder {
        private final AgUiClient client;
        private String threadId = UUID.randomUUID().toString();
        private ToolSource tools;
        private Predicate<ToolSpec> toolFilter = spec -> true;
        private ToolApproval approval;
        private CallerIdentity caller = CallerIdentity.ANONYMOUS;
        private List<RunAgentInput.Tool> clientTools = List.of();
        private ClientToolHandler clientToolHandler;
        private List<RunAgentInput.Context> context = List.of();
        private Object forwardedProps;
        private Object state;
        private List<AgUiMessage> messages = List.of();
        private Consumer<AgUiEvent> listener = e -> { };
        private int maxAutomaticRuns = 10;

        private Builder(AgUiClient client) {
            this.client = Objects.requireNonNull(client, "client");
        }

        /** Continue an existing conversation. Default: a new random id. */
        public Builder threadId(String threadId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId");
            return this;
        }

        /**
         * Offers the tools of {@code source} that {@code filter} accepts to the agent, and runs the
         * calls it makes to them. Names are adapted for model providers ({@code weather.forecast}
         * becomes {@code weather__forecast}).
         */
        public Builder tools(ToolSource source, Predicate<ToolSpec> filter) {
            this.tools = Objects.requireNonNull(source, "source");
            this.toolFilter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /** Consent for offered tools not marked read-only; without it such calls do not run. */
        public Builder approval(ToolApproval approval) {
            this.approval = Objects.requireNonNull(approval, "approval");
            return this;
        }

        /** Who offered tools run as. Default anonymous. */
        public Builder caller(CallerIdentity caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /** The application's own tools (for example UI actions), answered by {@code handler}. */
        public Builder clientTools(List<RunAgentInput.Tool> tools, ClientToolHandler handler) {
            this.clientTools = List.copyOf(tools);
            this.clientToolHandler = handler;
            return this;
        }

        public Builder context(List<RunAgentInput.Context> context) {
            this.context = List.copyOf(context);
            return this;
        }

        public Builder forwardedProps(Object props) {
            this.forwardedProps = props;
            return this;
        }

        /** The state the conversation starts from. Default the empty object. */
        public Builder state(Object state) {
            this.state = state;
            return this;
        }

        /** Messages the conversation starts with (for example restored history). */
        public Builder messages(List<AgUiMessage> messages) {
            this.messages = List.copyOf(messages);
            return this;
        }

        /** Sees every event of every run, after the thread applied it. */
        public Builder listener(Consumer<AgUiEvent> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Runs one turn may start on its own while answering tool calls. Default 10. */
        public Builder maxAutomaticRuns(int runs) {
            if (runs < 1) {
                throw new IllegalArgumentException("maxAutomaticRuns must be at least 1");
            }
            this.maxAutomaticRuns = runs;
            return this;
        }

        public AgUiThread build() {
            return new AgUiThread(this);
        }
    }
}
