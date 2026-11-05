package io.agentweave.evals.discovery.metrics;

import io.agentweave.evals.discovery.arms.ArmResult;
import io.agentweave.evals.discovery.arms.SelectedCall;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.GoldCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scores one {@link ArmResult} against the task's gold labels. Definitions (per task):
 * <ul>
 *   <li><b>no call</b>: the arm made no real tool call;</li>
 *   <li><b>selection correct</b>: the <em>first</em> real call targets a gold tool or an alternative the
 *       dataset accepts (MCPToolBench++ {@code similar_tools}, as in its own tool-consistency check);
 *       <b>strict</b>: gold tools only. A no-call task counts as incorrect;</li>
 *   <li><b>wrong calls</b>: real calls whose tool is neither gold nor accepted, including names
 *       that match no catalog tool; the arm-level <b>wrong-tool rate</b> is Σ wrong / Σ real calls;</li>
 *   <li><b>call correct</b> (labeled task-completion proxy): every gold call is matched by a
 *       distinct real call to the <em>same</em> gold tool whose arguments pass
 *       {@link ArgumentMatcher#match}; alternatives do not count (their schemas differ).
 *       {@code null} when the task has no gold arguments;</li>
 *   <li><b>argument keys correct</b>: the same with {@link ArgumentMatcher#keysMatch} (upper bound).</li>
 * </ul>
 */
public final class CallScorer {

    private CallScorer() {
    }

    /** Per-task scores; see the class comment. */
    public record TaskScore(String firstToolId, boolean noCall, boolean selectionCorrect,
                            boolean selectionCorrectStrict, int realCalls, int wrongCalls, Boolean callCorrect,
                            Boolean argKeysCorrect, List<String> argumentMismatches) {
    }

    public static TaskScore score(EvalTask task, EvalCatalog catalog, ArmResult result) {
        List<SelectedCall> calls = result.calls();
        String first = calls.isEmpty() ? null : calls.get(0).toolId();
        boolean noCall = calls.isEmpty();
        boolean correct = first != null && task.acceptsTool(first);
        boolean strict = first != null && task.goldRelevance().getOrDefault(first, 0) > 0;
        int wrong = (int) calls.stream().filter(c -> c.toolId() == null || !task.acceptsTool(c.toolId())).count();
        Boolean callCorrect = null;
        Boolean keysCorrect = null;
        List<String> mismatches = new ArrayList<>();
        if (!task.goldCalls().isEmpty()) {
            callCorrect = matchAll(task.goldCalls(), calls, catalog, true, mismatches);
            keysCorrect = matchAll(task.goldCalls(), calls, catalog, false, new ArrayList<>());
        }
        return new TaskScore(first, noCall, correct, strict, calls.size(), wrong, callCorrect, keysCorrect,
                List.copyOf(mismatches));
    }

    private static boolean matchAll(List<GoldCall> gold, List<SelectedCall> calls, EvalCatalog catalog,
                                    boolean values, List<String> mismatches) {
        boolean[] used = new boolean[calls.size()];
        boolean all = true;
        for (GoldCall g : gold) {
            EvalTool tool = catalog.tool(g.toolId());
            Map<String, Object> schema = tool == null ? Map.of() : tool.spec().inputSchema();
            boolean found = false;
            List<String> reasons = new ArrayList<>();
            for (int i = 0; i < calls.size() && !found; i++) {
                SelectedCall c = calls.get(i);
                if (used[i] || !g.toolId().equals(c.toolId())) {
                    continue;
                }
                ArgumentMatcher.Result r = values ? ArgumentMatcher.match(g.arguments(), c.arguments(), schema)
                        : ArgumentMatcher.keysMatch(g.arguments(), c.arguments(), schema);
                if (r.matched()) {
                    used[i] = true;
                    found = true;
                } else {
                    reasons.addAll(r.reasons());
                }
            }
            if (!found) {
                all = false;
                mismatches.add(g.toolId() + (reasons.isEmpty() ? ": not called" : ": " + String.join("; ", reasons)));
            }
        }
        return all;
    }
}
