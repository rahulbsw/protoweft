package io.agentweave.evals.discovery.metrics;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.arms.ArmResult;
import io.agentweave.evals.discovery.arms.SelectedCall;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.GoldCall;
import io.agentweave.evals.discovery.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallScorerTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("address", Map.of("type", "string")), "required", List.of("address"));
    private static final EvalTool GOLD = new EvalTool("t:gold", "test", new ToolSpec("geo", "", SCHEMA));
    private static final EvalTool ALT = new EvalTool("t:alt", "test", new ToolSpec("geo2", "", SCHEMA));
    private static final EvalTool OTHER = new EvalTool("t:other", "test", new ToolSpec("weather", "", SCHEMA));
    private static final EvalCatalog CATALOG = EvalCatalog.of(List.of(GOLD, ALT, OTHER));

    private static EvalTask task(List<GoldCall> calls) {
        return new EvalTask("task", "test", "q", null, Map.of("t:gold", 1), calls, Set.of("t:alt"));
    }

    private static ArmResult result(SelectedCall... calls) {
        return new ArmResult("arm", "task", List.of(calls), 0, 1, Usage.ZERO, 0, 3, 0, "", false, null, Map.of());
    }

    private static SelectedCall call(String id, String address) {
        return new SelectedCall(id, id == null ? "ghost" : id, Map.of("address", address), null);
    }

    @Test
    void correctToolAndArguments() {
        CallScorer.TaskScore s = CallScorer.score(task(List.of(new GoldCall("t:gold", Map.of("address", "Paris")))),
                CATALOG, result(call("t:gold", "paris")));
        assertThat(s.selectionCorrect()).isTrue();
        assertThat(s.selectionCorrectStrict()).isTrue();
        assertThat(s.callCorrect()).isTrue();
        assertThat(s.argKeysCorrect()).isTrue();
        assertThat(s.wrongCalls()).isZero();
        assertThat(s.noCall()).isFalse();
    }

    @Test
    void alternativeCountsForSelectionButNotForCallCorrectness() {
        CallScorer.TaskScore s = CallScorer.score(task(List.of(new GoldCall("t:gold", Map.of("address", "Paris")))),
                CATALOG, result(call("t:alt", "Paris")));
        assertThat(s.selectionCorrect()).isTrue();
        assertThat(s.selectionCorrectStrict()).isFalse();
        assertThat(s.callCorrect()).isFalse();
        assertThat(s.wrongCalls()).isZero();
    }

    @Test
    void wrongAndHallucinatedToolsAndNoCall() {
        EvalTask t = task(List.of(new GoldCall("t:gold", Map.of("address", "Paris"))));
        CallScorer.TaskScore s = CallScorer.score(t, CATALOG, result(call("t:other", "Paris"), call(null, "x"),
                call("t:gold", "Paris")));
        assertThat(s.firstToolId()).isEqualTo("t:other");
        assertThat(s.selectionCorrect()).isFalse();
        assertThat(s.realCalls()).isEqualTo(3);
        assertThat(s.wrongCalls()).isEqualTo(2);
        assertThat(s.callCorrect()).isTrue(); // a later call matched the gold call
        CallScorer.TaskScore none = CallScorer.score(t, CATALOG, result());
        assertThat(none.noCall()).isTrue();
        assertThat(none.selectionCorrect()).isFalse();
        assertThat(none.callCorrect()).isFalse();
        assertThat(none.argumentMismatches()).containsExactly("t:gold: not called");
    }

    @Test
    void multiCallGoldNeedsDistinctMatches() {
        EvalTask t = task(List.of(new GoldCall("t:gold", Map.of("address", "A")),
                new GoldCall("t:gold", Map.of("address", "B"))));
        assertThat(CallScorer.score(t, CATALOG, result(call("t:gold", "A"))).callCorrect()).isFalse();
        assertThat(CallScorer.score(t, CATALOG, result(call("t:gold", "B"), call("t:gold", "A"))).callCorrect()).isTrue();
    }

    @Test
    void noGoldArgumentsMeansNotApplicable() {
        CallScorer.TaskScore s = CallScorer.score(task(List.of()), CATALOG, result(call("t:gold", "x")));
        assertThat(s.callCorrect()).isNull();
        assertThat(s.argKeysCorrect()).isNull();
    }
}
