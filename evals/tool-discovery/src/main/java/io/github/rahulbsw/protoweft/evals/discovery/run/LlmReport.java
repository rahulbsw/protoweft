package io.github.rahulbsw.protoweft.evals.discovery.run;

import io.github.rahulbsw.protoweft.evals.discovery.cost.PriceTable;
import io.github.rahulbsw.protoweft.evals.discovery.metrics.Stats.Estimate;
import io.github.rahulbsw.protoweft.evals.discovery.report.Aggregator;
import io.github.rahulbsw.protoweft.evals.discovery.report.Aggregator.MetricDef;
import io.github.rahulbsw.protoweft.evals.discovery.report.Markdown;
import io.github.rahulbsw.protoweft.evals.discovery.report.RunInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Summary and Markdown report of an LLM run. Quality metrics exclude rows whose model call
 * failed (they are counted under "errors"); token, latency and cost metrics include every
 * completed call.
 */
final class LlmReport {

    static final String BASELINE_ARM = "all-tools";

    private static final List<MetricDef> QUALITY = List.of(
            new MetricDef("selection_accuracy", "selectionCorrect", true),
            new MetricDef("selection_accuracy_strict", "selectionCorrectStrict", true),
            new MetricDef("no_call_rate", "noCall", true),
            new MetricDef("call_correctness", "callCorrect", true),
            new MetricDef("arg_keys_correctness", "argKeysCorrect", true),
            new MetricDef("task_success_rate", "taskSuccess", true),
            new MetricDef("judged_success_rate", "judgedSuccess", true),
            new MetricDef("gold_tools_covered_rate", "goldToolsCovered", true),
            new MetricDef("re_fetches_per_task", "reFetches", false),
            new MetricDef("discovery_calls_per_task", "discoveryCalls", false));
    private static final List<MetricDef> COST = List.of(
            new MetricDef("model_calls_per_task", "modelCalls", false),
            new MetricDef("input_tokens_per_task", "inputTokens", false),
            new MetricDef("output_tokens_per_task", "outputTokens", false),
            new MetricDef("cached_input_tokens_per_task", "cachedInputTokens", false),
            new MetricDef("tool_payload_tokens_est_per_task", "toolPayloadTokensEst", false),
            new MetricDef("est_definition_tokens_per_task", "estDefinitionTokens", false),
            new MetricDef("est_result_tokens_per_task", "estResultTokens", false),
            new MetricDef("est_history_tokens_per_task", "estHistoryTokens", false),
            new MetricDef("est_input_tokens_per_task", "estInputTokens", false),
            new MetricDef("est_definition_tokens_per_call", "estDefinitionTokensPerCall", false),
            new MetricDef("reconciliation_residual_per_task", "reconciliationResidual", false),
            new MetricDef("reconciliation_residual_per_call", "reconciliationResidualPerCall", false),
            new MetricDef("exact_input_tokens_per_task", "exactInputTokens", false),
            new MetricDef("latency_ms_per_task", "latencyMs", false),
            new MetricDef("cost_usd_per_task", "costUsd", false),
            new MetricDef("worst_case_usd_per_task", "worstCaseUsd", false));

    private final LlmRun.Config config;
    private final PriceTable prices;
    private final Map<String, List<Map<String, Object>>> cells = new LinkedHashMap<>();

    LlmReport(LlmRun.Config config, PriceTable prices) {
        this.config = config;
        this.prices = prices;
    }

    void add(String cell, Map<String, Object> row) {
        cells.computeIfAbsent(cell, k -> new ArrayList<>()).add(row);
    }

    private static List<Map<String, Object>> ok(List<Map<String, Object>> rows) {
        return rows.stream().filter(r -> r.get("error") == null).toList();
    }

    private Map<String, Estimate> estimates(List<Map<String, Object>> rows) {
        Map<String, Estimate> e = new LinkedHashMap<>(Aggregator.summarize(ok(rows), QUALITY));
        e.put("wrong_tool_rate", Aggregator.pooledRatio(ok(rows), "wrongCalls", "realCalls"));
        e.putAll(Aggregator.summarize(rows, COST));
        return e;
    }

    /** Δ (arm − all-tools) for the same model and N, or empty if no baseline cell exists. */
    private Map<String, Estimate> deltas(String cell) {
        String[] k = cell.split("\\|");
        String base = k[0] + "|" + BASELINE_ARM + "|" + k[2];
        if (k[1].equals(BASELINE_ARM) || !cells.containsKey(base)) {
            return Map.of();
        }
        List<Map<String, Object>> a = cells.get(cell);
        List<Map<String, Object>> b = cells.get(base);
        Map<String, Estimate> d = new LinkedHashMap<>();
        d.put("selection_accuracy", Aggregator.pairedDifference(ok(a), ok(b), "selectionCorrect"));
        d.put("call_correctness", Aggregator.pairedDifference(ok(a), ok(b), "callCorrect"));
        d.put("input_tokens_per_task", Aggregator.pairedDifference(a, b, "inputTokens"));
        d.put("cost_usd_per_task", Aggregator.pairedDifference(a, b, "costUsd"));
        return d;
    }

    /** Dry runs: planned calls and the summed worst-case cost (priced models only). */
    private String planLine() {
        long calls = 0;
        double worst = 0;
        long unpriced = 0;
        for (List<Map<String, Object>> rows : cells.values()) {
            for (Map<String, Object> r : rows) {
                calls += ((Number) r.get("modelCalls")).longValue();
                if (r.get("worstCaseUsd") instanceof Number n) {
                    worst += n.doubleValue();
                } else {
                    unpriced++;
                }
            }
        }
        return String.format(Locale.ROOT, "Plan: %d model calls (single-turn estimate); worst-case total $%.2f"
                + " for priced models%s.%n%n", calls, worst, unpriced > 0 ? " (" + unpriced + " unpriced tasks excluded)" : "");
    }

    Map<String, Object> summary(RunInfo info, String stopReason, Map<String, Object> spend, List<String> notes) {
        Map<String, Object> s = new LinkedHashMap<>(info.toMap());
        s.put("stoppedEarly", stopReason != null);
        s.put("stopReason", stopReason);
        s.put("spend", spend);
        s.put("prices", prices.asMap());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> c : cells.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", c.getValue().size());
            m.put("errors", c.getValue().size() - ok(c.getValue()).size());
            estimates(c.getValue()).forEach((k, v) -> m.put(k, Markdown.toMap(v)));
            Map<String, Object> d = new LinkedHashMap<>();
            deltas(c.getKey()).forEach((k, v) -> d.put(k, Markdown.toMap(v)));
            m.put("delta_vs_" + BASELINE_ARM, d);
            out.put(c.getKey(), m);
        }
        s.put("cells", out);
        s.put("notes", notes);
        return s;
    }

    String markdown(RunInfo info, String stopReason, Map<String, Object> spend, List<String> notes) {
        StringBuilder md = new StringBuilder(Markdown.header(
                config.dryRun() ? "Tool discovery LLM evaluation — DRY RUN (no model calls)" : "Tool discovery LLM evaluation",
                info));
        if (stopReason != null) {
            md.append("> **Stopped early — partial results.** ").append(stopReason).append("\n\n");
        }
        if (config.dryRun()) {
            md.append("> Dry run: input tokens are jtokkit o200k estimates, no tool is selected (quality columns are "
                    + "meaningless), and `worst_case_usd` assumes the full output cap on every call.\n\n");
            md.append(planLine());
        }
        if (!spend.isEmpty()) {
            md.append(String.format(Locale.ROOT, "Spend: this run $%.4f over %s calls; ledger total $%.4f of $%.2f budget.%n%n",
                    (Double) spend.get("runSpentUsd"), spend.get("runCalls"), (Double) spend.get("ledgerTotalUsd"),
                    (Double) spend.get("budgetUsd")));
        }
        md.append("## Quality\n\nSelection = first real call is a gold (or dataset-accepted alternative) tool; ")
                .append("strict = gold only. Wrong-tool rate = non-gold calls / all calls (pooled). Call correctness = ")
                .append("AST argument match against `function_call_label` — **a proxy for task completion; tools are ")
                .append("not executed**. Arg keys = same, ignoring values (upper bound).\n\n");
        List<List<String>> q = new ArrayList<>();
        List<List<String>> c = new ArrayList<>();
        List<List<String>> d = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> cell : cells.entrySet()) {
            String[] k = cell.getKey().split("\\|");
            Map<String, Estimate> e = estimates(cell.getValue());
            int errors = cell.getValue().size() - ok(cell.getValue()).size();
            q.add(List.of(k[0], k[1], k[2], String.valueOf(ok(cell.getValue()).size()), String.valueOf(errors),
                    Markdown.fmt(e.get("selection_accuracy"), true), Markdown.fmt(e.get("selection_accuracy_strict"), true),
                    Markdown.fmt(e.get("no_call_rate"), true), Markdown.fmt(e.get("wrong_tool_rate"), true),
                    Markdown.fmt(e.get("call_correctness"), true), Markdown.fmt(e.get("arg_keys_correctness"), true),
                    Markdown.fmt(e.get("discovery_calls_per_task"), false)));
            c.add(List.of(k[0], k[1], k[2], Markdown.fmt(e.get("input_tokens_per_task"), false),
                    Markdown.fmt(e.get("cached_input_tokens_per_task"), false),
                    Markdown.fmt(e.get("tool_payload_tokens_est_per_task"), false),
                    Markdown.fmt(e.get("exact_input_tokens_per_task"), false),
                    Markdown.fmt(e.get("output_tokens_per_task"), false), Markdown.fmt(e.get("latency_ms_per_task"), false),
                    config.dryRun() ? Markdown.fmt(e.get("worst_case_usd_per_task"), false)
                            : Markdown.fmt(e.get("cost_usd_per_task"), false)));
            Map<String, Estimate> delta = deltas(cell.getKey());
            if (!delta.isEmpty()) {
                d.add(List.of(k[0], k[1], k[2], Markdown.fmt(delta.get("selection_accuracy"), true),
                        Markdown.fmt(delta.get("call_correctness"), true),
                        Markdown.fmt(delta.get("input_tokens_per_task"), false),
                        Markdown.fmt(delta.get("cost_usd_per_task"), false)));
            }
        }
        md.append(Markdown.table(List.of("model", "arm", "N", "tasks", "errors", "selection", "strict", "no-call",
                "wrong-tool", "call correct (proxy)", "arg keys", "discovery calls"), q));
        md.append("## Tokens, latency, cost (per task)\n\nInput tokens are provider `usage` (source of truth), ")
                .append("including cached tokens. Tool payload = jtokkit o200k estimate of the tool definitions sent ")
                .append("(approximate for Claude). Exact = Anthropic `count_tokens` when enabled. Cost needs ")
                .append("user-supplied prices; `–` means unpriced.\n\n");
        md.append(Markdown.table(List.of("model", "arm", "N", "input", "cached", "tool payload (est)", "exact input",
                "output", "latency ms", config.dryRun() ? "worst-case $" : "cost $"), c));
        if (!d.isEmpty()) {
            md.append("## Difference vs ").append(BASELINE_ARM).append(" (paired, same tasks)\n\n");
            md.append(Markdown.table(List.of("model", "arm", "N", "Δ selection", "Δ call correct", "Δ input tokens",
                    "Δ cost $"), d));
        }
        if (!notes.isEmpty()) {
            md.append("## Notes\n\n");
            notes.forEach(n -> md.append("- ").append(n).append('\n'));
            md.append('\n');
        }
        md.append("Caveats: call correctness is a deterministic proxy (paraphrased free-text arguments fail it); ")
                .append("tools are never executed; results hold only for the models, catalog sizes and data above.\n");
        return md.toString();
    }
}
