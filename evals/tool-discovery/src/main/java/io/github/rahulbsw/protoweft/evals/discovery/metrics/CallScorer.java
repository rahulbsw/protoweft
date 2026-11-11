package io.github.rahulbsw.protoweft.evals.discovery.metrics;

import io.github.rahulbsw.protoweft.evals.discovery.arms.ArmResult;
import io.github.rahulbsw.protoweft.evals.discovery.arms.SelectedCall;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.GoldCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * Per-task scores; see the class comment. State-based fields (for multi-step tasks):
     * <ul>
     *   <li><b>re-fetches</b>: real calls to a tool already called earlier in the task — the cost a
     *       result-shaping policy pays when the model re-asks for what was trimmed;</li>
     *   <li><b>gold tools covered</b>: every gold tool id appears among the real calls;</li>
     *   <li><b>required facts present</b>: the final answer contains every required fact
     *       (case-insensitive), or {@code null} when the task declares none;</li>
     *   <li><b>task success</b>: gold tools covered <em>and</em> required facts present (vacuously
     *       true when none declared) — a completion proxy that does not depend on exact arguments.</li>
     * </ul>
     */
    public record TaskScore(String firstToolId, boolean noCall, boolean selectionCorrect,
                            boolean selectionCorrectStrict, int realCalls, int wrongCalls, Boolean callCorrect,
                            Boolean argKeysCorrect, List<String> argumentMismatches,
                            int reFetches, boolean goldToolsCovered, Boolean requiredFactsPresent,
                            boolean taskSuccess) {
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
        int reFetches = reFetches(calls);
        boolean covered = goldToolsCovered(task, calls);
        Boolean factsPresent = requiredFactsPresent(task, result.finalText());
        boolean success = covered && (factsPresent == null || factsPresent);
        return new TaskScore(first, noCall, correct, strict, calls.size(), wrong, callCorrect, keysCorrect,
                List.copyOf(mismatches), reFetches, covered, factsPresent, success);
    }

    /** Real calls whose tool was already called earlier in the task. */
    private static int reFetches(List<SelectedCall> calls) {
        Set<String> seen = new LinkedHashSet<>();
        int repeats = 0;
        for (SelectedCall c : calls) {
            String key = c.toolId() != null ? c.toolId() : c.name();
            if (key != null && !seen.add(key)) {
                repeats++;
            }
        }
        return repeats;
    }

    private static boolean goldToolsCovered(EvalTask task, List<SelectedCall> calls) {
        List<String> gold = task.goldToolIds();
        if (gold.isEmpty()) {
            return false;
        }
        Set<String> called = new LinkedHashSet<>();
        calls.forEach(c -> called.add(c.toolId()));
        return called.containsAll(gold);
    }

    private static Boolean requiredFactsPresent(EvalTask task, String finalText) {
        if (task.requiredFacts().isEmpty()) {
            return null;
        }
        String hay = finalText == null ? "" : finalText.toLowerCase(Locale.ROOT);
        return task.requiredFacts().stream().allMatch(f -> hay.contains(f.toLowerCase(Locale.ROOT)));
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
