package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunError;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunFinished;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunOutcome;

import java.util.List;

/**
 * How a run ended, as the client saw it. Exactly one of three: {@link #finished()} (the agent
 * closed the run), {@link #error()} (the agent reported a failure), or {@link #truncated()} (the
 * stream ended without a terminal event: the run has no outcome and must not be read as success).
 *
 * @param finished          the run's {@code RUN_FINISHED}, or {@code null}
 * @param error             the run's {@code RUN_ERROR}, or {@code null} (also set when a failure was
 *                          reported after {@code RUN_FINISHED}, which then counts as failed)
 * @param truncated         the stream ended early, or the client cancelled it
 * @param unansweredCalls   tool calls the run started and did not answer, in call order
 * @param warnings          material the client dropped or stripped, and other non-fatal findings
 */
public record RunResult(RunFinished finished, RunError error, boolean truncated, List<String> unansweredCalls,
                        List<String> warnings) {

    public RunResult {
        unansweredCalls = List.copyOf(unansweredCalls);
        warnings = List.copyOf(warnings);
    }

    /** Completed: finished, not failed, not interrupted or cancelled. */
    public boolean succeeded() {
        return finished != null && error == null && !truncated
                && (finished.outcome() == null || finished.outcome() instanceof RunOutcome.Success);
    }

    public boolean interrupted() {
        return error == null && finished != null && finished.outcome() instanceof RunOutcome.Interrupted;
    }

    public boolean cancelled() {
        return error == null && finished != null && finished.outcome() instanceof RunOutcome.Cancelled;
    }

    /** The interrupts the run is waiting on (empty unless {@link #interrupted()}). */
    public List<Interrupt> interrupts() {
        return interrupted() ? ((RunOutcome.Interrupted) finished.outcome()).interrupts() : List.of();
    }

    /**
     * Tool calls the application must answer before the thread can continue: the
     * {@code pendingToolCallIds} the agent stated, or else every call it left unanswered (the spec
     * says absence does not mean "nothing pending").
     */
    public List<String> pendingToolCalls() {
        if (finished != null && finished.outcome() instanceof RunOutcome.Success s && s.pendingToolCallIds() != null
                && !s.pendingToolCallIds().isEmpty()) {
            return s.pendingToolCallIds();
        }
        return succeeded() || interrupted() ? unansweredCalls : List.of();
    }
}
