package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamVerifierTest {

    private static final Envelope N = Envelope.NONE;
    private static final RunStarted START = new RunStarted("t", "r", "1.0", null, null, N);
    private static final RunFinished FINISH = new RunFinished("t", "r", null, null, null, N);

    private static StreamVerifier feed(AgUiEvent... events) {
        StreamVerifier v = new StreamVerifier();
        for (AgUiEvent e : events) {
            v.accept(e);
        }
        return v;
    }

    private static void rejects(String message, AgUiEvent... events) {
        assertThatThrownBy(() -> feed(events)).isInstanceOf(AgUiFormatException.class).hasMessageContaining(message);
    }

    @Test
    void conformingStreamPasses() {
        assertThatCode(() -> feed(START,
                new StepStarted("s", N),
                new TextMessageStart("m", null, null, N),
                new ToolCallStart("c", "f", "m", N),        // interleaving is allowed
                new TextMessageContent("m", "hi", N),
                new ToolCallArgs("c", "{}", N),
                new ToolCallEnd("c", N),
                new TextMessageEnd("m", N),
                new TextMessageStart("m", null, null, N),   // a closed message may be reopened
                new TextMessageEnd("m", N),
                new ReasoningStart("x", N),
                new ReasoningMessageStart("x", N),         // spans and messages are separate namespaces
                new ReasoningMessageEnd("x", N),
                new ReasoningEnd("x", N),
                new StateSnapshot(null, N),
                new StepFinished("s", N),
                FINISH,
                new RunError("flush failed", null, null, N),   // admitted after RUN_FINISHED
                START, FINISH)).doesNotThrowAnyException();
    }

    @Test
    void lifecycleViolations() {
        rejects("must begin with RUN_STARTED", new TextMessageStart("m", null, null, N));
        rejects("still active", START, START);
        rejects("after the run closed", START, FINISH, new StateSnapshot(null, N));
        rejects("after the run closed", new RunError("x", null, null, N), new StateSnapshot(null, N));
        rejects("names run", START, new RunFinished("t", "other", null, null, null, N));
        assertThatCode(() -> feed(new RunError("unreachable", null, null, N))).doesNotThrowAnyException();
    }

    @Test
    void triadViolations() {
        rejects("not open", START, new TextMessageContent("m", "x", N));
        rejects("already open", START, new TextMessageStart("m", null, null, N), new TextMessageStart("m", null, null, N));
        rejects("still open: text message m", START, new TextMessageStart("m", null, null, N), FINISH);
        rejects("not open", START, new ReasoningStart("x", N), new ReasoningMessageContent("x", "orphan", N));
        rejects("unexpanded", START, new TextMessageChunk("m", null, "x", null, N));
        rejects("step 's' is not open", START, new StepFinished("s", N));
    }

    @Test
    void attributionMustAgree() {
        Envelope sub = Envelope.of("sub-1");
        rejects("is attributed to sub-2", START, new SubagentStarted("sub-1", "a", null, null, null, null, N),
                new TextMessageStart("m", null, null, sub), new TextMessageContent("m", "x", Envelope.of("sub-2")));
        // An untagged continuation continues whatever the opener owns.
        assertThatCode(() -> feed(START, new TextMessageStart("m", null, null, sub), new TextMessageEnd("m", N)))
                .doesNotThrowAnyException();
        // Steps are per owner: a subagent's step is not closed by the parent.
        rejects("not open for this owner", START, new StepStarted("s", sub), new StepFinished("s", N));
    }

    @Test
    void subagentLifecycle() {
        SubagentStarted a = new SubagentStarted("a", "x", null, null, null, null, N);
        rejects("already announced", START, a, new SubagentFinished("a", null, null, N), a);
        rejects("was not announced", START, new SubagentStarted("b", "y", null, "ghost", null, null, N));
        rejects("still open: subagent a", START, a, FINISH);
        rejects("already ended", START, a, new SubagentError("a", "x", null, N), new SubagentFinished("a", null, null, N));
    }

    @Test
    void pendingToolCallsMustMatchTheUnansweredCalls() {
        StreamVerifier v = feed(START, new ToolCallStart("c1", "f", null, N), new ToolCallEnd("c1", N),
                new ToolCallStart("c2", "g", null, N), new ToolCallEnd("c2", N), new ToolCallResult("m", "c2", "ok", N));
        assertThat(v.unansweredToolCalls()).containsExactly("c1");
        assertThatThrownBy(() -> v.accept(new RunFinished("t", "r", null,
                new RunOutcome.Success(List.of("c2")), null, N))).isInstanceOf(AgUiFormatException.class);
        assertThatCode(() -> v.accept(new RunFinished("t", "r", null,
                new RunOutcome.Success(List.of("c1")), null, N))).doesNotThrowAnyException();
    }

    @Test
    void interruptIdsAreUniqueInARun() {
        Interrupt i = Interrupt.of("i", "approval", null);
        rejects("not unique", START, new RunFinished("t", "r", null, new RunOutcome.Interrupted(List.of(i, i)), null, N));
    }
}
