package io.agentweave.evals.discovery.report;

import io.agentweave.evals.discovery.metrics.Stats;
import io.agentweave.evals.discovery.metrics.Stats.Estimate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorTest {

    private static Map<String, Object> row(String task, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", task);
        m.put("v", value);
        m.put("n", 2);
        return m;
    }

    @Test
    void singleTrialProportionsUseWilson() {
        List<Map<String, Object>> rows = List.of(row("a", true), row("b", true), row("c", false), row("d", null));
        Estimate e = Aggregator.estimate(rows, "v", true);
        assertThat(e).isEqualTo(Stats.wilson(2, 3));
    }

    @Test
    void repeatedTrialsAreAveragedPerTaskThenBootstrapped() {
        List<Map<String, Object>> rows = List.of(row("a", true), row("a", false), row("b", true), row("b", true));
        Estimate e = Aggregator.estimate(rows, "v", true);
        assertThat(e.method()).isEqualTo("bootstrap");
        assertThat(e.n()).isEqualTo(2);
        assertThat(e.mean()).isEqualTo(0.75);
    }

    @Test
    void pooledRatioAndPairedDifference() {
        List<Map<String, Object>> rows = List.of(row("a", 1), row("b", 0));
        Estimate pooled = Aggregator.pooledRatio(rows, "v", "n");
        assertThat(pooled.mean()).isEqualTo(0.25);
        assertThat(pooled.n()).isEqualTo(4);
        List<Map<String, Object>> a = List.of(row("x", 10), row("y", 20), row("only-a", 5));
        List<Map<String, Object>> b = List.of(row("x", 4), row("y", 6));
        Estimate d = Aggregator.pairedDifference(a, b, "v");
        assertThat(d.mean()).isEqualTo(10.0);
        assertThat(d.n()).isEqualTo(2);
        assertThat(Aggregator.estimate(List.of(), "v", false).n()).isZero();
    }

    @Test
    void markdownFormatting() {
        assertThat(Markdown.fmt(new Estimate(0.5, 0.25, 0.75, 10, "wilson"), true)).isEqualTo("50.0% [25.0, 75.0]");
        assertThat(Markdown.fmt(new Estimate(1234.4, 1200, 1300, 10, "bootstrap"), false)).isEqualTo("1234 [1200, 1300]");
        assertThat(Markdown.fmt(Estimate.empty(), false)).isEqualTo("–");
        assertThat(Markdown.table(List.of("a", "b"), List.of(List.of("1|x", "2"))))
                .isEqualTo("| a | b |\n|---|---|\n| 1\\|x | 2 |\n\n");
    }

    @Test
    void gitInfoNeverThrows() {
        GitInfo g = GitInfo.detect(Path.of("."));
        assertThat(g.commit()).isNotBlank();
        GitInfo none = GitInfo.detect(Path.of(System.getProperty("java.io.tmpdir")));
        assertThat(none.describe()).isNotBlank();
    }
}
