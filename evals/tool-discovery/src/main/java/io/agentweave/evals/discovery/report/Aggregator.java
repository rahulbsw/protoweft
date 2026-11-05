package io.agentweave.evals.discovery.report;

import io.agentweave.evals.discovery.metrics.Stats;
import io.agentweave.evals.discovery.metrics.Stats.Estimate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns per-unit rows (the JSONL records) into per-cell estimates. Rows are grouped by
 * {@code taskId}; repeated trials of a task are averaged first, so the statistical unit is
 * always the task. Proportions over single-trial tasks use Wilson; everything else the
 * bootstrap (see {@link Stats}). Booleans count as 1/0; null and missing values are skipped.
 */
public final class Aggregator {

    private Aggregator() {
    }

    /** A metric: report name, row field, and whether it is a 0/1 proportion. */
    public record MetricDef(String name, String field, boolean proportion) {
    }

    public static Map<String, Estimate> summarize(List<Map<String, Object>> rows, List<MetricDef> defs) {
        Map<String, Estimate> out = new java.util.LinkedHashMap<>();
        for (MetricDef d : defs) {
            out.put(d.name(), estimate(rows, d.field(), d.proportion()));
        }
        return out;
    }

    public static Estimate estimate(List<Map<String, Object>> rows, String field, boolean proportion) {
        Map<String, List<Double>> perTask = perTask(rows, field);
        if (perTask.isEmpty()) {
            return Estimate.empty();
        }
        boolean singleTrial = perTask.values().stream().allMatch(v -> v.size() == 1);
        if (proportion && singleTrial) {
            long successes = perTask.values().stream().filter(v -> v.get(0) >= 0.5).count();
            return Stats.wilson(successes, perTask.size());
        }
        return Stats.bootstrapMean(perTask.values().stream().mapToDouble(Aggregator::mean).toArray());
    }

    /** Σ numerator / Σ denominator over all rows, with a Wilson interval on the pooled counts. */
    public static Estimate pooledRatio(List<Map<String, Object>> rows, String numerator, String denominator) {
        long num = 0;
        long den = 0;
        for (Map<String, Object> r : rows) {
            Double n = value(r.get(numerator));
            Double d = value(r.get(denominator));
            if (n != null && d != null) {
                num += n.longValue();
                den += d.longValue();
            }
        }
        return Stats.wilson(num, den);
    }

    /** Paired difference mean(a − b) over tasks present (with a value) in both row sets. */
    public static Estimate pairedDifference(List<Map<String, Object>> a, List<Map<String, Object>> b, String field) {
        Map<String, List<Double>> pa = perTask(a, field);
        Map<String, List<Double>> pb = perTask(b, field);
        List<String> common = new ArrayList<>(pa.keySet());
        common.retainAll(pb.keySet());
        double[] x = common.stream().mapToDouble(t -> mean(pa.get(t))).toArray();
        double[] y = common.stream().mapToDouble(t -> mean(pb.get(t))).toArray();
        return x.length == 0 ? Estimate.empty() : Stats.pairedDifference(x, y);
    }

    private static Map<String, List<Double>> perTask(List<Map<String, Object>> rows, String field) {
        Map<String, List<Double>> perTask = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Double v = value(r.get(field));
            if (v != null) {
                perTask.computeIfAbsent(String.valueOf(r.get("taskId")), k -> new ArrayList<>()).add(v);
            }
        }
        return perTask;
    }

    private static Double value(Object o) {
        if (o instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) ? null : d;
        }
        return null;
    }

    private static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }
}
