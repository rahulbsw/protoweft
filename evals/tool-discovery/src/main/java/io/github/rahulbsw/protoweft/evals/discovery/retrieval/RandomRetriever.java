package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A chance-level baseline: a random ranking, seeded by (seed, task id), so it is deterministic.
 * Expected Recall@k ≈ k / N.
 */
public final class RandomRetriever implements Retriever {

    private final long seed;

    public RandomRetriever(long seed) {
        this.seed = seed;
    }

    @Override
    public String name() {
        return "random";
    }

    @Override
    public List<String> retrieve(EvalTask task, EvalCatalog catalog, int k) {
        List<String> ids = new ArrayList<>(catalog.tools().stream().map(EvalTool::id).sorted().toList());
        Collections.shuffle(ids, new Random(seed * 31 + task.id().hashCode()));
        return List.copyOf(ids.subList(0, Math.min(k, ids.size())));
    }
}
