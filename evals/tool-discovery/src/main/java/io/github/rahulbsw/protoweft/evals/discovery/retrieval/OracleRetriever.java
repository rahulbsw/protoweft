package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The upper bound: gold tools first (by descending grade, then label order), then the rest of
 * the catalog in id order. Recall@k is 1 whenever k ≥ the number of gold tools. Used to check
 * the harness and to bound what perfect retrieval would give an LLM arm.
 */
public final class OracleRetriever implements Retriever {

    @Override
    public String name() {
        return "oracle";
    }

    @Override
    public List<String> retrieve(EvalTask task, EvalCatalog catalog, int k) {
        List<Map.Entry<String, Integer>> gold = new ArrayList<>(task.goldRelevance().entrySet().stream()
                .filter(e -> e.getValue() > 0 && catalog.contains(e.getKey())).toList());
        gold.sort(Map.Entry.<String, Integer>comparingByValue().reversed()); // stable: label order within a grade
        Set<String> out = new LinkedHashSet<>();
        gold.forEach(e -> out.add(e.getKey()));
        catalog.tools().stream().map(EvalTool::id).sorted(Comparator.naturalOrder())
                .takeWhile(id -> out.size() < k).forEach(out::add);
        return List.copyOf(out).subList(0, Math.min(k, out.size()));
    }
}
