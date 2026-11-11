package io.github.rahulbsw.protoweft.evals.discovery.run;

import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.arms.Arms;
import io.github.rahulbsw.protoweft.evals.discovery.cost.CostGuard;
import io.github.rahulbsw.protoweft.evals.discovery.cost.PriceTable;
import io.github.rahulbsw.protoweft.evals.discovery.cost.SpendLedger;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalDataset;
import io.github.rahulbsw.protoweft.evals.discovery.data.GoldCall;
import io.github.rahulbsw.protoweft.evals.discovery.data.McpToolBenchLoader;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelResponse;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ScriptedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.DiscoveryRetrieverFactory;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.Retrievers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The discovery retrievers, calibration and arms inside the real runners, on the fixtures. */
class DiscoveryRunsTest {

    @Test
    void retrievalRunWithCatalogRetrievers(@TempDir Path out, @TempDir Path cache) throws Exception {
        RetrievalRun.Config c = new RetrievalRun.Config(List.of("mcptoolbench", "toolret:demo"),
                List.of("catalog-bm25", "catalog-hybrid"), List.of(1, 5), List.of(0), 20, 0, 7, "both",
                Fixtures.mcp(), Fixtures.toolret(), out, Path.of("."));
        Path dir = new RetrievalRun(c, n -> Retrievers.create(n, Map.of("embedding-cache", cache.toString()))).run();
        List<Map<String, Object>> rows = Json.readJsonl(dir.resolve("calls.jsonl"));
        assertThat(rows).hasSize((4 + 3) * 2);
        double recall5 = rows.stream().mapToDouble(r -> ((Number) r.get("recall@5")).doubleValue()).average().orElse(0);
        assertThat(recall5).isGreaterThan(0.7);
        assertThat(Files.readString(dir.resolve("report.md"))).contains("| catalog-hybrid |");
    }

    @Test
    void calibrateWritesASuggestionAndAppliesNothing(@TempDir Path out, @TempDir Path cache) throws Exception {
        CalibrateRun.Config c = new CalibrateRun.Config(List.of("mcptoolbench", "toolret:demo"),
                List.of("catalog-bm25", "catalog-hybrid"), List.of(0), 0, 7, "both", 0.5, 0.0, 1.0,
                Fixtures.mcp(), Fixtures.toolret(), out, Path.of("."));
        Path dir = new CalibrateRun(c, n -> DiscoveryRetrieverFactory.catalogRetriever(n,
                Map.of("embedding-cache", cache.toString()))).run();
        Map<String, Object> calibration = Json.asMap(Json.read(dir.resolve("calibration.json")));
        assertThat(calibration).containsEntry("applied", false).containsKeys("gitCommit", "datasets", "objective");
        Map<String, Object> suggestions = Json.asMap(calibration.get("suggestions"));
        assertThat(suggestions).containsOnlyKeys("catalog-bm25", "catalog-hybrid");
        assertThat(Json.asMap(suggestions.get("catalog-bm25"))).containsKeys("default", "suggested", "defaultDev",
                "suggestedTest", "devQueries", "testQueries");
        List<Map<String, Object>> rows = Json.readJsonl(dir.resolve("calls.jsonl"));
        assertThat(rows).hasSize((4 + 3) * 2).allSatisfy(r -> assertThat(r).containsKeys("level", "hit1", "hit5", "split"));
        assertThat(rows.stream().map(r -> r.get("split")).distinct()).containsExactlyInAnyOrder("dev", "test");
        assertThat(Files.readString(dir.resolve("report.md"))).contains("Suggestions only")
                .contains("Levels under the default policy").contains("## catalog-hybrid");
        assertThat(CalibrateRun.isDev("x", 1.0)).isTrue();
        assertThat(CalibrateRun.isDev("x", 0.0)).isFalse();
    }

    /** Searches with the query, then calls the gold capability (found in the search text) with gold arguments. */
    private static ScriptedModelClient searchingModel() {
        EvalDataset d = McpToolBenchLoader.load(Fixtures.mcp());
        Map<String, List<GoldCall>> byQuery = new HashMap<>();
        d.tasks().forEach(t -> byQuery.put(t.query(), t.goldCalls()));
        return new ScriptedModelClient("fake", "searcher", req -> {
            String q = ((Turn.User) req.conversation().get(0)).text();
            if (req.conversation().size() == 1) {
                return ScriptedModelClient.callTool(DiscoveryTools.SEARCH, Map.of("query", q), new Usage(500, 20, 0, 0));
            }
            GoldCall g = byQuery.get(q).get(0);
            String tool = g.toolId().substring(g.toolId().indexOf('/') + 1);
            String server = g.toolId().substring(g.toolId().indexOf(':') + 1, g.toolId().indexOf('/'));
            return new ModelResponse(new Turn.Assistant("", List.of(ToolCall.of("c1", DiscoveryTools.CALL,
                    Map.of("name", server + "." + tool, "arguments", g.arguments()))), null),
                    new Usage(800, 30, 0, 0), 3, "tool_use");
        });
    }

    @Test
    void llmRunScoresTheGenericSearchArmWithTheGuardOn(@TempDir Path out, @TempDir Path cache) {
        String model = "fake:searcher";
        PriceTable prices = PriceTable.empty().put(model, new PriceTable.Price(1, 10, 0.1, 1));
        CostGuard guard = new CostGuard(prices, 10, 0, SpendLedger.inMemory(), 1.3, 0, false);
        LlmRun.Config c = new LlmRun.Config(List.of("mcptoolbench"),
                List.of("generic-search:retriever=catalog-bm25,embedding-cache=" + cache), List.of(model), List.of(10),
                1, 0, 42, 128, 4, "shared", "both", 3, false, Map.of(), Fixtures.mcp(), Fixtures.toolret(), out,
                Path.of("."), null);
        Path dir = new LlmRun(c, Map.of(model, searchingModel()), guard, prices, Arms::create).run();
        List<Map<String, Object>> rows = Json.readJsonl(dir.resolve("calls.jsonl"));
        assertThat(rows).hasSize(4).allSatisfy(r -> {
            assertThat(r.get("arm")).isEqualTo("generic-search(catalog-bm25,inline=0,fallback=progressive)");
            assertThat(r.get("selectionCorrect")).isEqualTo(true);
            assertThat(r.get("discoveryCalls")).isEqualTo(1);
            assertThat(r.get("modelCalls")).isEqualTo(2);
            assertThat(r.get("inputTokens")).isEqualTo(1300);
            assertThat(r.get("error")).isNull();
        });
        assertThat(guard.runCalls()).isEqualTo(8);
    }
}
