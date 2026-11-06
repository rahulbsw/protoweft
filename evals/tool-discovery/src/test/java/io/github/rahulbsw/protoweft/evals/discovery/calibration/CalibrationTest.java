package io.github.rahulbsw.protoweft.evals.discovery.calibration;

import io.github.rahulbsw.protoweft.discovery.Confidence.Level;
import io.github.rahulbsw.protoweft.discovery.Confidence.Policy;
import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.calibration.Calibration.Signals;
import io.github.rahulbsw.protoweft.evals.discovery.calibration.Calibration.Suggestion;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalDataset;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.McpToolBenchLoader;
import io.github.rahulbsw.protoweft.evals.discovery.data.ToolRetLoader;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogRetriever;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.DiscoveryRetrieverFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationTest {

    @Test
    void levelMirrorsTheCatalogsOwnConfidence(@TempDir Path cache) {
        EvalDataset mcp = McpToolBenchLoader.load(Fixtures.mcp());
        EvalDataset toolret = ToolRetLoader.load(Fixtures.toolret(), List.of("demo"));
        List<EvalTool> pool = new ArrayList<>(mcp.tools().values());
        pool.addAll(toolret.tools().values());
        EvalCatalog catalog = EvalCatalog.builder().distractorPool(pool).size(0).seed(1).build();
        List<EvalTask> tasks = new ArrayList<>(mcp.tasks());
        tasks.addAll(toolret.tasks());
        List<String> queries = List.of("zzz unknown words", "geocode", "send an invoice by email", "create product");
        int checked = 0;
        for (String name : List.of("catalog-bm25", "catalog-dense", "catalog-hybrid")) {
            CatalogRetriever r = DiscoveryRetrieverFactory.catalogRetriever(name, Map.of("embedding-cache", cache.toString()));
            List<EvalTask> all = new ArrayList<>(tasks);
            queries.forEach(q -> all.add(new EvalTask("q:" + q, "test", q, null, Map.of(), null, null)));
            for (EvalTask t : all) {
                Signals s = Signals.of(r.search(t, catalog, 10).result(), false, false);
                assertThat(Calibration.level(Policy.DEFAULT, s)).as(name + ": " + t.query()).isEqualTo(s.reported());
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(20);
    }

    private static Signals kw(double coverage, double margin, boolean hit1, boolean hit5) {
        return new Signals(false, false, coverage, margin, false, Double.NaN, Level.MEDIUM, hit1, hit5);
    }

    @Test
    void gridSearchFindsThresholdsThatSeparateHitsFromMisses() {
        List<Signals> dev = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            dev.add(kw(0.9, 0.5, true, true));    // strong: hit at rank 1
            dev.add(kw(0.5, 0.01, false, true));  // middling: hit in the top 5
            dev.add(kw(0.1, 0.0, false, false));  // weak: miss
        }
        Suggestion s = Calibration.suggest(dev, dev, 0.10, 0.20);
        assertThat(s.feasible()).isTrue();
        assertThat(s.suggestedDev().precisionHigh()).isEqualTo(1.0);
        assertThat(s.suggestedDev().lowRecallOfMisses()).isEqualTo(1.0);
        assertThat(s.suggestedDev().lowFalseAlarm()).isEqualTo(0.0);
        assertThat(s.policy().highCoverage()).isGreaterThan(0.5).isLessThanOrEqualTo(0.9);
        assertThat(s.policy().lowCoverage()).isGreaterThan(0.1).isLessThanOrEqualTo(0.5);
        assertThat(s.objective()).isEqualTo(2.0);
        // The default policy calls the middling ones MEDIUM too, but its HIGH needs coverage >= 0.6.
        assertThat(s.defaultDev().counts()).containsEntry(Level.HIGH, 20).containsEntry(Level.LOW, 20);
    }

    @Test
    void infeasibleConstraintsKeepTheDefault() {
        List<Signals> dev = List.of(kw(0.1, 0, false, true), kw(0.1, 0, false, true));
        Suggestion s = Calibration.suggest(dev, List.of(), 0.9, 0.0);
        assertThat(s.feasible()).isFalse();
        assertThat(s.policy()).isEqualTo(Policy.DEFAULT);
        assertThat(s.defaultTest().n()).isZero();
    }

    @Test
    void semanticGridLeavesTheUnusedMarginAlone() {
        assertThat(Calibration.grid(true)).allMatch(p -> p.minMargin() == Policy.DEFAULT.minMargin()
                && p.lowCosine() <= p.highCosine() + 1e-9 && p.lowCoverage() <= p.highCoverage());
        assertThat(Calibration.grid(false)).allMatch(p -> p.highCosine() == Policy.DEFAULT.highCosine());
    }
}
