package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The server's {@link AgUiRun}: every event is checked by a {@link StreamVerifier} before it reaches
 * the sink, so a handler bug surfaces as {@link IllegalStateException} in the handler instead of a
 * malformed stream. Thread-safe; events are emitted in call order.
 */
final class RunEmitter extends EmitterScope implements AgUiRun {

    /** Where events go; an {@link IOException} means the client is gone. */
    @FunctionalInterface
    interface Sink {
        void send(AgUiEvent event) throws IOException;
    }

    private final RunAgentInput input;
    private final CallerIdentity caller;
    private final Sink sink;
    private final StreamVerifier verifier = new StreamVerifier();
    final Set<String> clientTools = new HashSet<>();
    final Set<String> activities = new HashSet<>();
    private static final System.Logger LOG = System.getLogger(RunEmitter.class.getName());

    private volatile boolean disconnected;
    private Runnable onDisconnect;
    private boolean ended;
    private List<TokenUsage> usage;

    RunEmitter(RunAgentInput input, CallerIdentity caller, Sink sink) {
        super(null);
        this.input = input;
        this.caller = caller;
        this.sink = sink;
        input.tools().forEach(t -> clientTools.add(t.name()));
    }

    /** Sends {@code RUN_STARTED}, declaring this module's protocol version. */
    void start() {
        emit(new RunStarted(input.threadId(), input.runId(), AgUiProtocol.VERSION, null, null, Envelope.NONE));
    }

    @Override
    RunEmitter core() {
        return this;
    }

    synchronized void emit(AgUiEvent event) {
        if (ended) {
            throw new IllegalStateException("the run has already ended; cannot send " + event.type());
        }
        try {
            verifier.accept(event);
        } catch (AgUiFormatException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (disconnected) {
            return;
        }
        try {
            sink.send(event);
        } catch (IOException | RuntimeException e) {
            disconnected();
        }
    }

    /** The client went away; nothing more can be delivered. Runs the disconnect hook once. */
    void disconnected() {
        Runnable hook;
        synchronized (this) {
            if (disconnected) {
                return;
            }
            disconnected = true;
            hook = onDisconnect;
        }
        if (hook != null) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "AG-UI disconnect hook failed", e);
            }
        }
    }

    /** Runs {@code hook} when the client goes away (at most once). */
    synchronized void onDisconnect(Runnable hook) {
        this.onDisconnect = hook;
    }

    synchronized boolean ended() {
        return ended;
    }

    /** The handler returned without ending the run: close what is open and finish. */
    synchronized void endIfOpen() {
        if (!ended) {
            closeOpen();
            finish(null);
        }
    }

    /** The handler threw: end the run as failed unless it already ended. */
    synchronized void failIfOpen(String message) {
        if (!ended) {
            end(new RunError(message, null, usage, Envelope.NONE));
        }
    }

    @Override
    public RunAgentInput input() {
        return input;
    }

    @Override
    public CallerIdentity caller() {
        return caller;
    }

    @Override
    public boolean isCancelled() {
        return disconnected;
    }

    @Override
    public synchronized void usage(List<TokenUsage> usage) {
        this.usage = usage == null ? null : List.copyOf(usage);
    }

    @Override
    public synchronized void finish(Object result) {
        List<String> pending = verifier.unansweredToolCalls();
        end(new RunFinished(input.threadId(), input.runId(), result,
                pending.isEmpty() ? null : new RunOutcome.Success(pending), usage, Envelope.NONE));
    }

    @Override
    public synchronized void interrupt(List<Interrupt> interrupts) {
        end(new RunFinished(input.threadId(), input.runId(), null, new RunOutcome.Interrupted(interrupts), usage,
                Envelope.NONE));
    }

    @Override
    public synchronized void cancel() {
        if (!ended) {
            closeOpen();
            end(new RunFinished(input.threadId(), input.runId(), null, new RunOutcome.Cancelled(), usage,
                    Envelope.NONE));
        }
    }

    @Override
    public synchronized void fail(String message, String code) {
        end(new RunError(Objects.requireNonNull(message, "message"), code, usage, Envelope.NONE));
    }

    private void end(AgUiEvent terminal) {
        emit(terminal);
        ended = true;
    }

    /** Closes every open item, innermost first, so the run can finish conformantly. */
    private void closeOpen() {
        StreamVerifier.Open open = verifier.open();
        open.messages().forEach(id -> emit(new TextMessageEnd(id, Envelope.NONE)));
        open.toolCalls().forEach(id -> emit(new ToolCallEnd(id, Envelope.NONE)));
        open.reasoningMessages().forEach(id -> emit(new ReasoningMessageEnd(id, Envelope.NONE)));
        open.reasoningSpans().forEach(id -> emit(new ReasoningEnd(id, Envelope.NONE)));
        open.steps().forEach(s -> emit(new StepFinished(s.name(), Envelope.of(s.owner()))));
        open.subagents().forEach(id -> emit(new SubagentFinished(id, null, null, Envelope.NONE)));
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

}
