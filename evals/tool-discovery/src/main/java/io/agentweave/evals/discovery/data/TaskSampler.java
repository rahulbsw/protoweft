package io.agentweave.evals.discovery.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic, stratified task sampling: tasks are grouped by {@link EvalTask#dataset()}
 * (for MCPToolBench++ that is the category), each group is sorted by id and shuffled with
 * {@code Random(seed)}, then groups are visited round-robin (in dataset-name order) until
 * {@code n} tasks are taken. So every category is represented as evenly as its size allows,
 * and the same seed always yields the same sample. {@code n <= 0} or {@code n >= size} returns
 * all tasks unchanged.
 */
public final class TaskSampler {

    private TaskSampler() {
    }

    public static List<EvalTask> sample(List<EvalTask> tasks, int n, long seed) {
        if (n <= 0 || n >= tasks.size()) {
            return List.copyOf(tasks);
        }
        Map<String, List<EvalTask>> groups = new TreeMap<>();
        for (EvalTask t : tasks) {
            groups.computeIfAbsent(t.dataset(), k -> new ArrayList<>()).add(t);
        }
        List<List<EvalTask>> queues = new ArrayList<>();
        for (List<EvalTask> g : groups.values()) {
            g.sort(Comparator.comparing(EvalTask::id));
            Collections.shuffle(g, new Random(seed));
            queues.add(g);
        }
        List<EvalTask> out = new ArrayList<>(n);
        for (int round = 0; out.size() < n; round++) {
            boolean any = false;
            for (List<EvalTask> q : queues) {
                if (round < q.size() && out.size() < n) {
                    out.add(q.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return List.copyOf(out);
    }
}
