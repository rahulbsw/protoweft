package io.agentweave.evals.discovery.arms;

import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.llm.ModelClient;

/**
 * One way of exposing a catalog's tools to a model. An arm builds the request(s) for a task,
 * runs them through the given client (single-turn, or a {@link ToolLoop} for arms with a search
 * or discovery tool) and reports what the model selected.
 *
 * <p>Arms do not score: {@link io.agentweave.evals.discovery.metrics.CallScorer} compares the
 * result with the gold labels. Arms do not see the cost guard: the client they get is already
 * guarded, and a refused call propagates as
 * {@link io.agentweave.evals.discovery.cost.BudgetExceededException}.
 */
public interface Arm {

    /** Stable name for reports, including its parameters, e.g. {@code top-k(k=5,oracle)}. */
    String name();

    ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client);
}
