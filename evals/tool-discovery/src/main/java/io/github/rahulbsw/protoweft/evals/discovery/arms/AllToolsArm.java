package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;

import java.util.Map;

/**
 * The baseline: every catalog tool's full schema in a single request; the model's tool call(s)
 * in that one reply are its selection.
 */
public final class AllToolsArm implements Arm {

    private final int maxOutputTokens;
    private final TokenEstimator estimator;

    public AllToolsArm(int maxOutputTokens, TokenEstimator estimator) {
        this.maxOutputTokens = maxOutputTokens;
        this.estimator = estimator;
    }

    @Override
    public String name() {
        return "all-tools";
    }

    @Override
    public ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client) {
        ToolLoop.Outcome o = ToolLoop.run(client, Prompts.SYSTEM, Prompts.user(task), catalog.providerSpecs(),
                maxOutputTokens, 1, (call, turn) -> ToolLoop.Decision.select(), estimator);
        return ToolLoop.toResult(name(), task.id(), o, catalog, Map.of());
    }
}
