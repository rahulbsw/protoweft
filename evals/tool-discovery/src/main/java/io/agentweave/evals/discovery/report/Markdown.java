package io.agentweave.evals.discovery.report;

import io.agentweave.evals.discovery.data.DatasetInfo;
import io.agentweave.evals.discovery.metrics.Stats.Estimate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Markdown building blocks shared by the reports. */
public final class Markdown {

    private Markdown() {
    }

    /** The common header: run id, commit, data provenance, settings and the CI method. */
    public static String header(String title, RunInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("- Run: `").append(info.runId()).append("` started ").append(info.startedAt()).append('\n');
        sb.append("- Commit: `").append(info.git().commit()).append('`')
                .append(info.git().dirty() ? " **(dirty working tree: results are not bound to a clean commit)**" : " (clean)")
                .append('\n');
        sb.append("- Datasets:\n");
        for (DatasetInfo d : info.datasets()) {
            sb.append("  - ").append(d.name()).append(" — revision `").append(d.revision()).append("`, sha256 `")
                    .append(d.sha256()).append("`, ").append(d.counts()).append(" (").append(d.source()).append(")\n");
        }
        sb.append("- Settings: `").append(info.config()).append("`\n");
        sb.append("- Intervals: 95%; Wilson score for single-trial proportions, otherwise percentile bootstrap over ")
                .append("tasks (2000 resamples, fixed seed). Δ columns are paired bootstrap differences; an interval ")
                .append("containing 0 means no difference was detected, not that the arms are equivalent.\n\n");
        return sb.toString();
    }

    /** {@code mean [low, high]}; proportions as percentages. */
    public static String fmt(Estimate e, boolean percent) {
        if (e == null || e.n() == 0 || Double.isNaN(e.mean())) {
            return "–";
        }
        if (percent) {
            return String.format(Locale.ROOT, "%.1f%% [%.1f, %.1f]", 100 * e.mean(), 100 * e.low(), 100 * e.high());
        }
        double m = Math.abs(e.mean());
        String f = m >= 100 ? "%.0f [%.0f, %.0f]" : m >= 1 ? "%.2f [%.2f, %.2f]" : "%.4f [%.4f, %.4f]";
        return String.format(Locale.ROOT, f, e.mean(), e.low(), e.high());
    }

    public static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.size())).append('\n');
        for (List<String> r : rows) {
            sb.append("| ").append(String.join(" | ", r.stream().map(Markdown::cell).toList())).append(" |\n");
        }
        return sb.append('\n').toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /** Estimate as a JSON-friendly map. */
    public static Map<String, Object> toMap(Estimate e) {
        return Map.of("mean", nanSafe(e.mean()), "low", nanSafe(e.low()), "high", nanSafe(e.high()), "n", e.n(),
                "method", e.method());
    }

    private static Object nanSafe(double d) {
        return Double.isNaN(d) ? "NaN" : d;
    }
}
