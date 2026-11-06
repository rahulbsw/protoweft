package io.github.rahulbsw.protoweft.evals.discovery.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One evaluation query with its gold labels. Model-neutral: nothing here depends on a provider.
 *
 * @param id               unique task id
 * @param dataset          dataset (and subset) the task comes from, e.g. {@code mcptoolbench/map}
 * @param query            the user request
 * @param instruction      optional retrieval instruction (ToolRet), or {@code null}
 * @param goldRelevance    gold tool ids with graded relevance (grade &gt; 0 means relevant);
 *                         multi-label; iteration order is the dataset's label order
 * @param goldCalls        gold calls with arguments, when the dataset has them (MCPToolBench++);
 *                         empty otherwise
 * @param alternativeTools tool ids the dataset also accepts as a correct tool choice
 *                         (MCPToolBench++ {@code similar_tools}); never relevant for retrieval
 *                         metrics and never used for argument matching
 */
public record EvalTask(String id, String dataset, String query, String instruction,
                       Map<String, Integer> goldRelevance, List<GoldCall> goldCalls,
                       Set<String> alternativeTools) {

    public EvalTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(query, "query");
        goldRelevance = goldRelevance == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(goldRelevance));
        goldCalls = goldCalls == null ? List.of() : List.copyOf(goldCalls);
        alternativeTools = alternativeTools == null ? Set.of() : Set.copyOf(alternativeTools);
    }

    /** Gold tool ids (relevance &gt; 0), in label order. */
    public List<String> goldToolIds() {
        return goldRelevance.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
    }

    public Optional<String> instructionText() {
        return Optional.ofNullable(instruction).filter(s -> !s.isBlank());
    }

    /** Whether choosing {@code toolId} counts as a correct tool choice (gold or accepted alternative). */
    public boolean acceptsTool(String toolId) {
        return goldRelevance.getOrDefault(toolId, 0) > 0 || alternativeTools.contains(toolId);
    }
}
