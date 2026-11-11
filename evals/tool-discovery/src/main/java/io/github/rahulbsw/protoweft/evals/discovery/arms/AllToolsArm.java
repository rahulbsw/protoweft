package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;

import io.github.rahulbsw.protoweft.evals.discovery.shaping.HistoryPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.ResultPolicy;

import java.util.List;
import java.util.Map;

/**
 * The baseline: every catalog tool's full schema in a single request; the model's tool call(s)
 * in that one reply are its selection. With a {@link MultiTurn} context (opt-in {@code --multiturn}),
 * each selected call is answered with a mocked result and the loop continues over the task's gold
 * trajectory, so result and history tokens are exercised and counted.
 */
public final class AllToolsArm implements Arm {

    private final int maxOutputTokens;
    private final TokenEstimator estimator;
    private final MultiTurn multiTurn;
    private final ResultPolicy resultPolicy;
    private final HistoryPolicy historyPolicy;

    public AllToolsArm(int maxOutputTokens, TokenEstimator estimator) {
        this(maxOutputTokens, estimator, null);
    }

    public AllToolsArm(int maxOutputTokens, TokenEstimator estimator, MultiTurn multiTurn) {
        this(maxOutputTokens, estimator, multiTurn, ResultPolicy.raw(), HistoryPolicy.full());
    }

    public AllToolsArm(int maxOutputTokens, TokenEstimator estimator, MultiTurn multiTurn,
                       ResultPolicy resultPolicy, HistoryPolicy historyPolicy) {
        this.maxOutputTokens = maxOutputTokens;
        this.estimator = estimator;
        this.multiTurn = multiTurn;
        this.resultPolicy = resultPolicy;
        this.historyPolicy = historyPolicy;
    }

    @Override
    public String name() {
        return "all-tools" + Arms.shapingSuffix(multiTurn, resultPolicy, historyPolicy);
    }

    @Override
    public ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client) {
        ToolLoop.MockMode mock = multiTurn == null ? null : multiTurn.mode(task, resultPolicy, historyPolicy);
        int turns = multiTurn == null ? 1 : multiTurn.turnsFor(task);
        ToolLoop.Outcome o = ToolLoop.run(client, Prompts.SYSTEM, Prompts.user(task), catalog.providerSpecs(),
                List.of(), maxOutputTokens, turns, (call, turn) -> ToolLoop.Decision.select(), estimator, mock);
        return ToolLoop.toResult(name(), task.id(), o, catalog, Map.of());
    }
}
