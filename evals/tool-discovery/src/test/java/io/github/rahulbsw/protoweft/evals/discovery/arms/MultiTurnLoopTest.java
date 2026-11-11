package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.GoldCall;
import io.github.rahulbsw.protoweft.evals.discovery.fixtures.FixtureStore;
import io.github.rahulbsw.protoweft.evals.discovery.fixtures.MockResultProvider;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ScriptedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTurnLoopTest {

    private static final TokenEstimator EST = TokenEstimator.o200k();
    private static final EvalTool GEO = Fixtures.tool("g:geo", "geocode");
    private static final EvalTool REV = Fixtures.tool("g:rev", "reverse");
    private static final EvalCatalog CATALOG = EvalCatalog.builder().required(List.of(GEO, REV))
            .distractorPool(Fixtures.distractors(20)).size(6).seed(3).build();
    private static final EvalTask TWO_STEP = new EvalTask("t2", "test", "Where is Paris and what is nearby?",
            null, Map.of("g:geo", 1, "g:rev", 1),
            List.of(new GoldCall("g:geo", Map.of("q", "Paris")), new GoldCall("g:rev", Map.of("lat", 48.85))),
            Set.of());

    /** Calls geocode, then reverse, then stops — driven by how many mocked results came back. */
    private static ScriptedModelClient twoStepModel() {
        return new ScriptedModelClient("fake", "m", req -> {
            long rounds = req.conversation().stream().filter(t -> t instanceof Turn.ToolResults).count();
            if (rounds == 0) {
                return ScriptedModelClient.callTool("geocode", Map.of("q", "Paris"), new Usage(500, 20, 0, 0));
            }
            if (rounds == 1) {
                return ScriptedModelClient.callTool("reverse", Map.of("lat", 48.85), new Usage(500, 20, 0, 0));
            }
            return ScriptedModelClient.text("done", new Usage(50, 5, 0, 0));
        });
    }

    private static MultiTurn multiTurn() {
        MockResultProvider provider = new MockResultProvider(FixtureStore.empty(), EST, 128, 7);
        return new MultiTurn(provider, 8);
    }

    @Test
    void mockModeFeedsResultsBackAndRunsTheTrajectory() {
        ScriptedModelClient model = twoStepModel();
        ArmResult r = new AllToolsArm(256, EST, multiTurn()).run(TWO_STEP, CATALOG, model);

        // Two tool turns (geocode, reverse) plus a final no-tool synthesis turn.
        assertThat(model.requests()).hasSize(3);
        assertThat(r.calls()).extracting(SelectedCall::toolId).containsExactly("g:geo", "g:rev");
        assertThat(r.modelCalls()).isEqualTo(3);
        // The first turn's mocked result is sent on the second request, so result tokens are counted.
        assertThat(r.estimated().results()).isPositive();
        // Definitions are counted cumulatively over all three turns (tools stay on the synthesis turn).
        assertThat(r.estimated().definitions()).isEqualTo(3 * EST.tools(CATALOG.providerSpecs()));
        // History grows: the second request carries the first user + assistant turns.
        assertThat(r.estimated().history()).isPositive();
    }

    @Test
    void reconciliationResidualIsProviderMinusEstimate() {
        ScriptedModelClient model = twoStepModel();
        ArmResult r = new AllToolsArm(256, EST, multiTurn()).run(TWO_STEP, CATALOG, model);
        assertThat(r.reconciliationResidual()).isEqualTo(r.usage().inputTokens() - r.estimated().total());
        // Two tool turns at 500 input each, plus the synthesis turn at 50.
        assertThat(r.usage().inputTokens()).isEqualTo(1050);
    }

    @Test
    void perRequestReconciliationIsRecordedAndDefinitionsStayFlatPerCall() {
        ScriptedModelClient model = twoStepModel();
        ArmResult r = new AllToolsArm(256, EST, multiTurn()).run(TWO_STEP, CATALOG, model);
        // One per-request record per model call: two tool turns plus the synthesis turn.
        assertThat(r.perRequest()).hasSize(3);
        // Per-call definitions are flat (same catalog each turn) and equal the single-request size,
        // while cumulative definitions are the sum over the two tool requests (synthesis sends none).
        long perCall = EST.tools(CATALOG.providerSpecs());
        assertThat(r.definitionTokensPerCall()).isEqualTo(perCall);
        assertThat(r.estimated().definitions()).isEqualTo(3 * perCall);
        // The two tool turns have real input 500; the synthesis turn is the text reply (50).
        assertThat(r.perRequest().get(0).realInput()).isEqualTo(500);
        assertThat(r.perRequest().get(2).realInput()).isEqualTo(50);
        assertThat(r.reconciliationResidualPerCall())
                .isEqualTo(Math.round((double) r.reconciliationResidual() / 3));
    }

    @Test
    void singleTurnIsUnchangedWhenMultiTurnIsOff() {
        ScriptedModelClient model = twoStepModel();
        ArmResult r = new AllToolsArm(256, EST).run(TWO_STEP, CATALOG, model);
        assertThat(model.requests()).hasSize(1);
        assertThat(r.calls()).extracting(SelectedCall::toolId).containsExactly("g:geo");
        assertThat(r.estimated().results()).isZero();
    }
}
