package io.github.rahulbsw.protoweft.evals.discovery.metrics;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ScriptedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LlmJudgeTest {

    private static final EvalTask TASK = new EvalTask("t", "test", "Count the users and report it", null,
            Map.of(), List.of(), Set.of(), List.of("1284"));

    @Test
    void parsesPassVerdictEvenWithSurroundingText() {
        LlmJudge.Verdict v = LlmJudge.parse("Here is my verdict: {\"pass\": true, \"reason\": \"says 1284\"} done");
        assertThat(v.pass()).isTrue();
        assertThat(v.reason()).isEqualTo("says 1284");
    }

    @Test
    void missingOrUnparseableVerdictFails() {
        assertThat(LlmJudge.parse("no json here").pass()).isFalse();
        assertThat(LlmJudge.parse(null).pass()).isFalse();
        assertThat(LlmJudge.parse("{broken").pass()).isFalse();
    }

    @Test
    void judgeCallsModelAndReturnsVerdict() {
        ScriptedModelClient model = new ScriptedModelClient("fake", "judge",
                req -> ScriptedModelClient.text("{\"pass\": false, \"reason\": \"no count reported\"}", new Usage(80, 10, 0, 0)));
        LlmJudge.Verdict v = new LlmJudge(model).judge(TASK, "I ran some queries.");
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).isEqualTo("no count reported");
        // The judge sends one no-tool request carrying the task and the answer.
        assertThat(model.requests()).hasSize(1);
        assertThat(model.requests().get(0).tools()).isEmpty();
        assertThat(((io.github.rahulbsw.protoweft.evals.discovery.llm.Turn.User) model.requests().get(0).conversation().get(0)).text())
                .contains("Count the users").contains("I ran some queries").contains("1284");
    }
}
