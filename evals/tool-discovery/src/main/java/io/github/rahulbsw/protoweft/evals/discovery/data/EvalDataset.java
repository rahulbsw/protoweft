package io.github.rahulbsw.protoweft.evals.discovery.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loaded tools and tasks of one dataset.
 *
 * @param tools tools by {@link EvalTool#id()}, in load order
 * @param tasks tasks in load order
 * @param info  provenance
 */
public record EvalDataset(Map<String, EvalTool> tools, List<EvalTask> tasks, DatasetInfo info) {

    public EvalDataset {
        tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        tasks = List.copyOf(tasks);
    }
}
