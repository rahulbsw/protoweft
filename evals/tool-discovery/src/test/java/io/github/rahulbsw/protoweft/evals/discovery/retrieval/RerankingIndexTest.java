package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.discovery.CapabilityIndex;
import io.github.rahulbsw.protoweft.discovery.CapabilityIndex.Document;
import io.github.rahulbsw.protoweft.discovery.CapabilityIndex.Field;
import io.github.rahulbsw.protoweft.discovery.CapabilityIndex.Hit;
import io.github.rahulbsw.protoweft.discovery.HybridIndex;
import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalDataset;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.McpToolBenchLoader;
import io.github.rahulbsw.protoweft.evals.discovery.metrics.RetrievalMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@link RerankingIndex} with a fake scorer, plus the factory options (real model only if downloaded). */
class RerankingIndexTest {

    /** Returns fixed hits t0..t(n-1), scores n..1, with distinct signals. */
    static final class FixedIndex implements CapabilityIndex {
        final List<Hit> hits = new ArrayList<>();
        int lastLimit;

        FixedIndex(int n) {
            for (int i = 0; i < n; i++) {
                hits.add(new Hit("t" + i, n - i, i + 1, 10.0 - i, 0.5, i + 1, 0.9 - i * 0.01));
            }
        }

        @Override
        public void upsert(Collection<Document> documents) {
        }

        @Override
        public void remove(Collection<String> ids) {
        }

        @Override
        public Result search(String query, Predicate<String> candidates, int limit, double minCosine) {
            lastLimit = limit;
            List<Hit> h = hits.stream().filter(x -> candidates.test(x.id())).limit(limit).toList();
            return new Result(h, true);
        }

        @Override
        public boolean semantic() {
            return true;
        }
    }

    /** Scores passage "t<i>" by the given table (passages are ids: no documents upserted). */
    private static RerankingIndex.Scorer table(Map<String, Float> scores, AtomicReference<List<String>> seen) {
        return (q, passages) -> {
            seen.set(passages);
            float[] out = new float[passages.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = scores.getOrDefault(passages.get(i), 0f);
            }
            return out;
        };
    }

    @Test
    void reranksTheWindowAndKeepsTheRestInBaseOrder() {
        FixedIndex base = new FixedIndex(6);
        AtomicReference<List<String>> seen = new AtomicReference<>();
        RerankingIndex index = new RerankingIndex(base, table(Map.of("t2", 5f, "t1", 3f, "t0", 3f), seen), 4,
                RerankingIndex.Text.CARD, Double.NaN);

        List<Hit> hits = index.search("q", id -> true, 6, 0.3).hits();

        assertThat(seen.get()).containsExactly("t0", "t1", "t2", "t3"); // only the window is scored
        assertThat(hits).extracting(Hit::id).containsExactly("t2", "t0", "t1", "t3", "t4", "t5"); // tie: base order
        assertThat(hits.get(0).score()).isCloseTo(1 / (1 + Math.exp(-5)), within(1e-9));
        assertThat(hits.get(4).score()).isZero();
        Hit t2 = hits.get(0);
        assertThat(t2.keywordRank()).isEqualTo(3);
        assertThat(t2.keywordScore()).isEqualTo(8.0);
        assertThat(t2.vectorRank()).isEqualTo(3);
        assertThat(t2.cosine()).isCloseTo(0.88, within(1e-9));
        assertThat(base.lastLimit).isEqualTo(6);
    }

    @Test
    void asksTheBaseForTheWholeWindowEvenForASmallLimit() {
        FixedIndex base = new FixedIndex(10);
        RerankingIndex index = new RerankingIndex(base, table(Map.of("t7", 9f), new AtomicReference<>()), 8,
                RerankingIndex.Text.CARD, Double.NaN);

        List<Hit> hits = index.search("q", id -> true, 2, 0.3).hits();

        assertThat(base.lastLimit).isEqualTo(8);
        assertThat(hits).extracting(Hit::id).containsExactly("t7", "t0");
    }

    @Test
    void mixBlendsMinMaxNormalizedScoresWithinTheWindow() {
        FixedIndex base = new FixedIndex(3); // base scores 3, 2, 1 -> z 1, 0.5, 0
        Map<String, Float> rr = Map.of("t0", 0f, "t1", 10f, "t2", 5f); // z 0, 1, 0.5
        RerankingIndex pureBase = new RerankingIndex(base, table(rr, new AtomicReference<>()), 3,
                RerankingIndex.Text.CARD, 0);
        RerankingIndex pureRerank = new RerankingIndex(base, table(rr, new AtomicReference<>()), 3,
                RerankingIndex.Text.CARD, 1);
        RerankingIndex mixed = new RerankingIndex(base, table(rr, new AtomicReference<>()), 3,
                RerankingIndex.Text.CARD, 0.3);

        assertThat(pureBase.search("q", id -> true, 3, 0).hits()).extracting(Hit::id).containsExactly("t0", "t1", "t2");
        assertThat(pureRerank.search("q", id -> true, 3, 0).hits()).extracting(Hit::id)
                .containsExactly("t1", "t2", "t0");
        List<Hit> m = mixed.search("q", id -> true, 3, 0).hits();
        // t0: 0.7*1 = 0.7; t1: 0.3 + 0.35 = 0.65; t2: 0.15
        assertThat(m).extracting(Hit::id).containsExactly("t0", "t1", "t2");
        assertThat(m.get(0).score()).isCloseTo(0.7, within(1e-9));
        assertThat(m.get(1).score()).isCloseTo(0.65, within(1e-9));
        assertThat(m.get(2).score()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void emptyResultsAndBlankQueriesSkipTheReranker() {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        RerankingIndex index = new RerankingIndex(new FixedIndex(3), table(Map.of(), seen), 3,
                RerankingIndex.Text.CARD, Double.NaN);
        assertThat(index.search("q", id -> false, Integer.MAX_VALUE, 0).hits()).isEmpty();
        assertThat(index.search(" ", id -> true, 2, 0).hits()).extracting(Hit::id).containsExactly("t0", "t1");
        assertThat(seen.get()).isNull();
        assertThat(RerankingIndex.takeRerankStats()).isEqualTo(RerankingIndex.RerankStats.NONE);
        index.search("q", id -> true, 2, 0);
        RerankingIndex.RerankStats s = RerankingIndex.takeRerankStats();
        assertThat(s.pairs()).isEqualTo(3);
        assertThat(s.calls()).isEqualTo(1);
        assertThat(RerankingIndex.takeRerankStats().calls()).isZero();
    }

    @Test
    void passagesUseCardOrDescriptionFields() {
        Document d = new Document("src.getCurrentWeather", Map.of(
                Field.NAME, "getCurrentWeather", Field.SOURCE, "weather-server", Field.TITLE, "Weather",
                Field.SUMMARY, "Current weather.", Field.TAGS, "", Field.PARAMETERS, "city the city name",
                Field.DESCRIPTION, "Current weather. Longer text."));
        assertThat(RerankingIndex.passage(d, RerankingIndex.Text.CARD))
                .isEqualTo("get Current Weather. Weather. Current weather.. city the city name");
        assertThat(RerankingIndex.passage(d, RerankingIndex.Text.DESCRIPTION))
                .isEqualTo("get Current Weather. Weather. Current weather. Longer text.. city the city name");
        assertThat(RerankingIndex.spellName("playwright_iframe_click")).isEqualTo("playwright iframe click");
        assertThat(RerankingIndex.spellName("HTTPServer-v2")).isEqualTo("HTTP Server v2");
    }

    @Test
    void rejectsBadParameters() {
        RerankingIndex.Scorer none = (q, p) -> new float[p.size()];
        assertThatThrownBy(() -> new RerankingIndex(new FixedIndex(1), none, 0, RerankingIndex.Text.CARD, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RerankingIndex(new FixedIndex(1), none, 5, RerankingIndex.Text.CARD, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoryDefaultsToTheProductFusionAndParsesRerankOptions(@TempDir Path dir) {
        assertThat(DiscoveryRetrieverFactory.fusion(Map.of())).isEqualTo(HybridIndex.Fusion.DEFAULT);
        assertThat(DiscoveryRetrieverFactory.fusion(Map.of("fusion", "rrf")).kind()).isEqualTo(HybridIndex.Fusion.Kind.RRF);
        assertThat(DiscoveryRetrieverFactory.rerank(Map.of())).isNull();
        assertThatThrownBy(() -> DiscoveryRetrieverFactory.rerank(Map.of("rerank", "l9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscoveryRetrieverFactory.rerank(Map.of("rerank", "l6", "reranker-dir", dir.toString())))
                .hasMessageContaining("no reranker model");
        assertThatThrownBy(() -> DiscoveryRetrieverFactory.catalogRetriever("catalog-bm25:rerank=l6",
                Map.of("reranker-dir", dir.toString()))).isInstanceOf(IllegalArgumentException.class);
    }

    /** End to end with the real L6 model, when downloaded ({@code scripts/fetch_reranker.py}). */
    @Test
    void realRerankerOnTheFixtures(@TempDir Path cache) {
        Path model = Path.of("build/models/ms-marco-minilm-l6-v2");
        assumeTrue(Files.isRegularFile(model.resolve("model.onnx")), "reranker model not downloaded");
        EvalDataset mcp = McpToolBenchLoader.load(Fixtures.mcp());
        EvalCatalog c = EvalCatalog.builder().required(mcp.tools().values()).distractorPool(List.<EvalTool>of())
                .size(0).seed(7).build();
        CatalogRetriever r = DiscoveryRetrieverFactory.catalogRetriever(
                "catalog-hybrid:rerank=l6:rerank-depth=5:rerank-mix=0.5",
                Map.of("embedding-cache", cache.toString(), "reranker-dir", model.toString()));
        r.index(c);
        double recall = 0;
        for (EvalTask t : mcp.tasks()) {
            List<String> top = r.retrieve(t, c, 3);
            Map<String, Object> extras = r.rowExtras();
            assertThat(extras).containsKeys("rerankMs", "rerankPairs");
            assertThat((Integer) extras.get("rerankPairs")).isPositive();
            recall += RetrievalMetrics.recallAt(top, t.goldRelevance(), 3);
        }
        assertThat(recall / mcp.tasks().size()).isGreaterThanOrEqualTo(0.75);
    }
}
