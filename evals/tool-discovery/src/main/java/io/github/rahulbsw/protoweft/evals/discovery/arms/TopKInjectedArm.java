package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.Retriever;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.HistoryPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.ResultPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Retrieval before the model: the retriever ranks the catalog for the task's query and only the
 * top-k full schemas go into a single request. No discovery calls; selection is bounded by the
 * retriever's recall, which is logged per task ({@code goldInjected}).
 */
public final class TopKInjectedArm implements Arm {

    private final Retriever retriever;
    private final int k;
    private final int maxOutputTokens;
    private final TokenEstimator estimator;
    private final MultiTurn multiTurn;
    private final ResultPolicy resultPolicy;
    private final HistoryPolicy historyPolicy;
    private String indexedFingerprint;

    public TopKInjectedArm(Retriever retriever, int k, int maxOutputTokens, TokenEstimator estimator) {
        this(retriever, k, maxOutputTokens, estimator, null);
    }

    public TopKInjectedArm(Retriever retriever, int k, int maxOutputTokens, TokenEstimator estimator,
                           MultiTurn multiTurn) {
        this(retriever, k, maxOutputTokens, estimator, multiTurn, ResultPolicy.raw(), HistoryPolicy.full());
    }

    public TopKInjectedArm(Retriever retriever, int k, int maxOutputTokens, TokenEstimator estimator,
                           MultiTurn multiTurn, ResultPolicy resultPolicy, HistoryPolicy historyPolicy) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        this.k = k;
        this.maxOutputTokens = maxOutputTokens;
        this.estimator = estimator;
        this.multiTurn = multiTurn;
        this.resultPolicy = resultPolicy;
        this.historyPolicy = historyPolicy;
    }

    @Override
    public String name() {
        return "top-k(k=" + k + "," + retriever.name() + ")" + Arms.shapingSuffix(multiTurn, resultPolicy, historyPolicy);
    }

    @Override
    public synchronized ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client) {
        if (!catalog.fingerprint().equals(indexedFingerprint)) {
            retriever.index(catalog);
            indexedFingerprint = catalog.fingerprint();
        }
        List<String> ids = retriever.retrieve(task, catalog, k).stream().filter(catalog::contains).limit(k).toList();
        ToolLoop.MockMode mock = multiTurn == null ? null : multiTurn.mode(task, resultPolicy, historyPolicy);
        int turns = multiTurn == null ? 1 : multiTurn.turnsFor(task);
        ToolLoop.Outcome o = ToolLoop.run(client, Prompts.SYSTEM, Prompts.user(task), catalog.providerSpecs(ids),
                List.of(), maxOutputTokens, turns, (call, turn) -> ToolLoop.Decision.select(), estimator, mock);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("retrieved", ids);
        extras.put("goldInjected", ids.containsAll(task.goldToolIds()));
        return ToolLoop.toResult(name(), task.id(), o, catalog, extras);
    }
}
