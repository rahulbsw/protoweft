package io.agentweave.evals.discovery.retrieval;

import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.Embedder;
import io.agentweave.evals.discovery.Fixtures;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalDataset;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.McpToolBenchLoader;
import io.agentweave.evals.discovery.data.ToolRetLoader;
import io.agentweave.evals.discovery.metrics.RetrievalMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The discovery-backed retrievers on the bundled fixtures (offline; the real offline embedders). */
class CatalogRetrieversTest {

    private static final EvalDataset MCP = McpToolBenchLoader.load(Fixtures.mcp());
    private static final EvalDataset TOOLRET = ToolRetLoader.load(Fixtures.toolret(), List.of("demo"));

    /** MCPToolBench++ fixture gold tools plus every fixture tool as distractor. */
    private static EvalCatalog catalog() {
        List<EvalTool> pool = new ArrayList<>(MCP.tools().values());
        pool.addAll(TOOLRET.tools().values());
        return EvalCatalog.builder().required(MCP.tools().values()).distractorPool(pool).size(0).seed(7).build();
    }

    private static Map<String, String> opts(Path cache) {
        return Map.of("embedding-cache", cache.toString());
    }

    private static double meanRecall(Retriever r, EvalCatalog c, List<EvalTask> tasks, int k) {
        r.index(c);
        return tasks.stream().mapToDouble(t -> RetrievalMetrics.recallAt(r.retrieve(t, c, k), t.goldRelevance(), k))
                .average().orElse(0);
    }

    @Test
    void registeredThroughTheFactory(@TempDir Path cache) {
        assertThat(Retrievers.available()).contains("catalog-bm25", "catalog-dense", "catalog-hybrid");
        assertThat(Retrievers.create("catalog-hybrid", opts(cache))).isInstanceOfSatisfying(
                CatalogRetriever.class, r -> assertThat(r.ranking()).isEqualTo(CatalogRetriever.Ranking.HYBRID));
        assertThat(((CatalogRetriever) Retrievers.create("catalog-dense", opts(cache))).ranking())
                .isEqualTo(CatalogRetriever.Ranking.DENSE);
    }

    @Test
    void bridgeUsesOneSourcePerServerAndMapsNamesBack(@TempDir Path cache) {
        EvalCatalog c = catalog();
        CatalogRetriever r = (CatalogRetriever) Retrievers.create("catalog-bm25", opts(cache));
        CatalogBridge.Built b = r.bridge(c);
        assertThat(b.catalog().size()).isEqualTo(c.size());
        assertThat(b.catalog().sourceNames()).contains("toolret-web").hasSizeGreaterThan(1);
        for (EvalTool t : c.tools()) {
            String capability = b.capabilities().get(t.id());
            assertThat(capability).isEqualTo(CatalogBridge.sourceOf(t) + "." + c.providerName(t.id()));
            assertThat(b.toolId(capability)).isEqualTo(t.id());
        }
        assertThat(r.bridge(c)).isSameAs(b); // same fingerprint: not rebuilt
    }

    @Test
    void recallSanityOnTheFixtures(@TempDir Path cache) {
        EvalCatalog c = catalog();
        List<EvalTask> tasks = MCP.tasks();
        for (String name : List.of("catalog-bm25", "catalog-dense", "catalog-hybrid")) {
            Retriever r = Retrievers.create(name, opts(cache));
            assertThat(meanRecall(r, c, tasks, 3)).as(name + " recall@3").isGreaterThanOrEqualTo(0.75);
            assertThat(r.retrieve(tasks.get(0), c, 4)).hasSizeLessThanOrEqualTo(4).allMatch(c::contains)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void searchesReportTheProductsConfidenceAndMode(@TempDir Path cache) {
        EvalCatalog c = catalog();
        CatalogRetriever bm25 = (CatalogRetriever) Retrievers.create("catalog-bm25", opts(cache));
        CatalogRetriever hybrid = (CatalogRetriever) Retrievers.create("catalog-hybrid", opts(cache));
        EvalTask t = MCP.tasks().get(0);
        assertThat(bm25.search(t, c, 5).result().mode()).isEqualTo(DiscoveryResult.Mode.KEYWORD_ONLY);
        CatalogRetriever.Search s = hybrid.search(t, c, 5);
        assertThat(s.result().mode()).isEqualTo(DiscoveryResult.Mode.HYBRID);
        assertThat(s.result().confidence()).isNotNull();
        assertThat(s.toolIds()).hasSize(Math.min(5, s.result().hits().size()));
    }

    @Test
    void denseRanksByCosineAlone(@TempDir Path cache) {
        EvalCatalog c = catalog();
        CatalogRetriever dense = (CatalogRetriever) Retrievers.create("catalog-dense", opts(cache));
        DiscoveryResult r = dense.search(MCP.tasks().get(1), c, 10).result();
        List<Double> scores = r.hits().stream().map(DiscoveryResult.Hit::score).toList();
        assertThat(scores).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(scores).allSatisfy(s -> assertThat(s).isGreaterThanOrEqualTo(0.30));
    }

    @Test
    void instructionsArePrefixedOnlyWhenAsked(@TempDir Path cache) {
        EvalTask withInstruction = TOOLRET.tasks().stream().filter(t -> t.instructionText().isPresent()).findFirst()
                .orElseThrow();
        CatalogRetriever plain = (CatalogRetriever) Retrievers.create("catalog-bm25", opts(cache));
        CatalogRetriever prefixed = (CatalogRetriever) Retrievers.create("catalog-bm25",
                Map.of("instruction", "true", "embedding-cache", cache.toString()));
        assertThat(plain.queryText(withInstruction)).isEqualTo(withInstruction.query());
        assertThat(prefixed.queryText(withInstruction)).startsWith(withInstruction.instruction().strip())
                .endsWith(withInstruction.query());
    }

    /** Counts delegate calls; vectors depend on the text only. */
    static final class CountingEmbedder implements Embedder {
        final AtomicInteger documents = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();

        @Override
        public String name() {
            return "counting/test";
        }

        @Override
        public int dimension() {
            return 3;
        }

        @Override
        public float[] embedDocument(String text) {
            documents.incrementAndGet();
            return new float[] {text.length(), text.hashCode() % 7, 1};
        }

        @Override
        public float[] embedQuery(String text) {
            queries.incrementAndGet();
            return new float[] {1, text.length(), 0};
        }
    }

    @Test
    void embeddingCacheHitsAcrossInstancesAndSurvivesATruncatedTail(@TempDir Path cache) throws Exception {
        CountingEmbedder base = new CountingEmbedder();
        CachingEmbedder first = new CachingEmbedder(base, cache);
        List<float[]> v1 = first.embedDocuments(List.of("alpha", "beta", "alpha"));
        first.embedQuery("q");
        assertThat(base.documents).hasValue(2); // "alpha" embedded once
        assertThat(first.misses()).isEqualTo(3);
        assertThat(first.file().getFileName().toString()).isEqualTo("counting_test.vec");

        CachingEmbedder second = new CachingEmbedder(base, cache);
        List<float[]> v2 = second.embedDocuments(List.of("beta", "alpha"));
        assertThat(second.embedQuery("q")).containsExactly(1, 1, 0);
        assertThat(base.documents).hasValue(2); // all served from disk
        assertThat(base.queries).hasValue(1);
        assertThat(second.hits()).isEqualTo(3);
        assertThat(second.misses()).isZero();
        assertThat(v2.get(1)).containsExactly(v1.get(0));
        assertThat(v2.get(0)).containsExactly(v1.get(1));

        // An interrupted write leaves a partial record: it is cut off and the rest still loads.
        long size = Files.size(first.file());
        Files.write(first.file(), new byte[] {0, 1, 2}, java.nio.file.StandardOpenOption.APPEND);
        CachingEmbedder third = new CachingEmbedder(base, cache);
        assertThat(third.size()).isEqualTo(3);
        assertThat(Files.size(first.file())).isEqualTo(size);
        third.embedDocuments(List.of("gamma"));
        assertThat(new CachingEmbedder(base, cache).size()).isEqualTo(4);
    }

    @Test
    void catalogEmbeddingsAreServedFromTheCacheOnRepeatedRuns(@TempDir Path cache) {
        EvalCatalog c = catalog();
        CountingEmbedder base = new CountingEmbedder();
        new CatalogRetriever("x", CatalogRetriever.Ranking.HYBRID, new CachingEmbedder(base, cache),
                DiscoveryRetrieverFactory.fallback("none"), false).index(c);
        int embedded = base.documents.get();
        assertThat(embedded).isEqualTo(c.size());
        CachingEmbedder again = new CachingEmbedder(base, cache);
        new CatalogRetriever("x", CatalogRetriever.Ranking.HYBRID, again, DiscoveryRetrieverFactory.fallback("none"),
                false).index(c);
        assertThat(base.documents).hasValue(embedded);
        assertThat(again.hits()).isEqualTo(c.size());
        assertThat(again.misses()).isZero();
    }
}
