package io.github.rahulbsw.protoweft.evals.discovery.run;

import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.arms.Arms;
import io.github.rahulbsw.protoweft.evals.discovery.cost.CostGuard;
import io.github.rahulbsw.protoweft.evals.discovery.cost.PriceTable;
import io.github.rahulbsw.protoweft.evals.discovery.cost.SpendLedger;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalDataset;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.GoldCall;
import io.github.rahulbsw.protoweft.evals.discovery.data.McpToolBenchLoader;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClients;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelResponse;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ScriptedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.Retrievers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunsTest {

    private static final String MODEL = "fake:perfect";
    private static final PriceTable PRICES = PriceTable.empty().put(MODEL, new PriceTable.Price(1, 10, 0.1, 1));

    /** Calls the gold tool(s) with gold arguments whenever they are offered (names are unsuffixed gold names). */
    private static ScriptedModelClient perfectModel() {
        EvalDataset d = McpToolBenchLoader.load(Fixtures.mcp());
        Map<String, List<GoldCall>> byQuery = new HashMap<>();
        d.tasks().forEach(t -> byQuery.put(t.query(), t.goldCalls()));
        return new ScriptedModelClient("fake", "perfect", req -> {
            String q = ((Turn.User) req.conversation().get(0)).text();
            List<ToolCall> calls = new ArrayList<>();
            for (GoldCall g : byQuery.get(q)) {
                String name = g.toolId().substring(g.toolId().indexOf('/') + 1);
                if (req.tools().stream().anyMatch(t -> t.name().equals(name))) {
                    calls.add(ToolCall.of("c" + calls.size(), name, g.arguments()));
                }
            }
            return new ModelResponse(new Turn.Assistant("", calls, null), new Usage(1000, 50, 0, 0), 5, "tool_use");
        });
    }

    private static LlmRun.Config config(Path out, List<Integer> sizes, boolean dryRun) {
        return new LlmRun.Config(List.of("mcptoolbench"), List.of("all-tools", "top-k:k=2,retriever=oracle"),
                List.of(MODEL), sizes, 1, 0, 42, 128, 4, "shared", "both", 3, dryRun, Map.of(),
                Fixtures.mcp(), Fixtures.toolret(), out, Path.of("."), null);
    }

    private static List<Map<String, Object>> rows(Path dir) {
        return Json.readJsonl(dir.resolve("calls.jsonl"));
    }

    @Test
    void llmRunScoresAPerfectModelAndWritesReports(@TempDir Path out) throws Exception {
        CostGuard guard = new CostGuard(PRICES, 10, 0, SpendLedger.inMemory(), 1.3, 0, false);
        Path dir = new LlmRun(config(out, List.of(10, 5, 3), false), Map.of(MODEL, perfectModel()), guard, PRICES,
                Arms::create).run();
        List<Map<String, Object>> rows = rows(dir);
        assertThat(rows).hasSize(4 * 2 * 2); // 4 tasks x 2 arms x N in {5, 10}; N=3 < 4 gold tools is skipped
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("selectionCorrect")).isEqualTo(true);
            assertThat(r.get("callCorrect")).isEqualTo(true);
            assertThat(r.get("error")).isNull();
            assertThat((Double) r.get("costUsd")).isEqualTo(0.0015);
        });
        assertThat(rows.stream().map(r -> r.get("catalogSize")).distinct()).containsExactly(5, 10);
        assertThat(rows.stream().filter(r -> r.get("arm").equals("all-tools")).map(r -> r.get("toolsOffered")).distinct())
                .containsExactlyInAnyOrder(5, 10);
        Map<String, Object> summary = Json.asMap(Json.read(dir.resolve("summary.json")));
        assertThat(summary).containsEntry("stoppedEarly", false).containsKeys("gitCommit", "gitDirty", "datasets");
        assertThat(Json.asList(summary.get("notes")).toString()).contains("N=3 skipped");
        String md = Files.readString(dir.resolve("report.md"));
        assertThat(md).contains("| fake:perfect | all-tools | 5 | 4 | 0 | 100.0% [51.0, 100.0]")
                .contains("Difference vs all-tools").contains("Commit: `").contains("MCPToolBench++")
                .contains("fixture-rev-1").doesNotContain("Stopped early");
        assertThat(guard.runCalls()).isEqualTo(16);
    }

    @Test
    void budgetStopLeavesABalancedPartialReport(@TempDir Path out) throws Exception {
        // Each call costs $0.0015; the worst-case estimate is ~$0.003, so a $0.01 budget allows a few calls.
        CostGuard guard = new CostGuard(PRICES, 0.01, 0, SpendLedger.inMemory(), 1.3, 0, false);
        Path dir = new LlmRun(config(out, List.of(5), false), Map.of(MODEL, perfectModel()), guard, PRICES,
                Arms::create).run();
        List<Map<String, Object>> rows = rows(dir);
        assertThat(rows).isNotEmpty().hasSizeLessThan(8);
        assertThat(rows).hasSize((int) guard.runCalls());
        Map<String, Object> summary = Json.asMap(Json.read(dir.resolve("summary.json")));
        assertThat(summary).containsEntry("stoppedEarly", true);
        assertThat(String.valueOf(summary.get("stopReason"))).contains("global budget");
        assertThat(Files.readString(dir.resolve("report.md"))).contains("Stopped early — partial results");
        assertThat(guard.runSpentUsd()).isLessThanOrEqualTo(0.01);
    }

    @Test
    void zeroBudgetMakesNoCalls(@TempDir Path out) {
        ScriptedModelClient model = perfectModel();
        CostGuard guard = new CostGuard(PRICES, 0, 0, SpendLedger.inMemory(), 1.3, 0, false);
        Path dir = new LlmRun(config(out, List.of(5), false), Map.of(MODEL, model), guard, PRICES, Arms::create).run();
        assertThat(model.requests()).isEmpty();
        assertThat(rows(dir)).isEmpty();
    }

    @Test
    void dryRunNeedsNoGuardAndEstimatesTokens(@TempDir Path out) throws Exception {
        ModelClient dry = ModelClients.estimating(MODEL, TokenEstimator.o200k());
        Path dir = new LlmRun(config(out, List.of(5), true), Map.of(MODEL, dry), null, PRICES, Arms::create).run();
        List<Map<String, Object>> rows = rows(dir);
        assertThat(rows).hasSize(8).allSatisfy(r -> {
            assertThat(((Number) r.get("inputTokens")).longValue()).isPositive();
            assertThat(r.get("worstCaseUsd")).isNotNull();
        });
        assertThat(Files.readString(dir.resolve("report.md"))).contains("DRY RUN").contains("Plan: 8 model calls");
    }

    @Test
    void perTaskCatalogsAreSeededPerTask(@TempDir Path out) {
        LlmRun.Config c = config(out, List.of(6), false);
        LlmRun.Config perTask = new LlmRun.Config(c.datasets(), List.of("all-tools"), c.models(), c.catalogSizes(), 1, 0,
                42, 128, 4, "per-task", "both", 3, false, Map.of(), c.mcpDir(), c.toolretDir(), out, c.repoDir(), null);
        CostGuard guard = new CostGuard(PRICES, 10, 0, SpendLedger.inMemory(), 1.3, 0, false);
        Path dir = new LlmRun(perTask, Map.of(MODEL, perfectModel()), guard, PRICES, Arms::create).run();
        List<Map<String, Object>> rows = rows(dir);
        assertThat(rows).hasSize(4).allSatisfy(r -> assertThat(r.get("toolsOffered")).isEqualTo(6));
        assertThat(rows.stream().map(r -> r.get("catalogFingerprint")).distinct().count()).isGreaterThan(1);
    }

    @Test
    void retrievalRunOnFixtures(@TempDir Path out) throws Exception {
        RetrievalRun.Config c = new RetrievalRun.Config(List.of("mcptoolbench", "toolret:demo"),
                List.of("oracle", "random"), List.of(1, 3), List.of(0, 8), 20, 0, 7, "both",
                Fixtures.mcp(), Fixtures.toolret(), out, Path.of("."));
        Path dir = new RetrievalRun(c, n -> Retrievers.create(n, Map.of("seed", "7"))).run();
        List<Map<String, Object>> rows = rows(dir);
        // mcptoolbench: 4 tasks; toolret demo: 3 tasks; 2 retrievers; 2 sizes
        assertThat(rows).hasSize((4 + 3) * 2 * 2);
        assertThat(rows.stream().filter(r -> r.get("retriever").equals("oracle")))
                .allSatisfy(r -> assertThat(r.get("mrr")).isEqualTo(1.0));
        String md = Files.readString(dir.resolve("report.md"));
        assertThat(md).contains("| toolret:demo | oracle | 8 | 3 | 1.00 [1.00, 1.00]").contains("ToolRet");
        Map<String, Object> summary = Json.asMap(Json.read(dir.resolve("summary.json")));
        assertThat(Json.asMap(summary.get("cells"))).containsKey("mcptoolbench|oracle|8");
    }

    @Test
    void catalogHelpersUsedByRunsAreConsistent() {
        EvalDataset d = McpToolBenchLoader.load(Fixtures.mcp());
        EvalCatalog c = EvalCatalog.of(d.tools().values());
        for (EvalTask t : d.tasks()) {
            assertThat(c.requiredIds()).isEmpty();
            t.goldToolIds().forEach(id -> assertThat(c.contains(id)).isTrue());
        }
    }
}
