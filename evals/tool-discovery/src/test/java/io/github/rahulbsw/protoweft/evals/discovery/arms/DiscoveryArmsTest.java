package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.cost.BudgetExceededException;
import io.github.rahulbsw.protoweft.evals.discovery.cost.CostGuard;
import io.github.rahulbsw.protoweft.evals.discovery.cost.GuardedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.cost.PriceTable;
import io.github.rahulbsw.protoweft.evals.discovery.cost.SpendLedger;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.GoldCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelRequest;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelResponse;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ScriptedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The generic search-tool arm with a scripted model: search → describe → call. Offline. */
class DiscoveryArmsTest {

    static final EvalTool GOLD = Fixtures.tool("g:geo", "geocode");
    static final EvalCatalog CATALOG = EvalCatalog.builder().required(List.of(GOLD))
            .distractorPool(Fixtures.distractors(30)).size(20).seed(3).build();
    static final EvalTask TASK = new EvalTask("t1", "test", "Where is Paris?", null, Map.of("g:geo", 1),
            List.of(new GoldCall("g:geo", Map.of("q", "Paris"))), Set.of());
    static final TokenEstimator EST = TokenEstimator.o200k();

    static Map<String, String> opts(Path cache, String... kv) {
        Map<String, String> m = new LinkedHashMap<>(Map.of("retriever", "catalog-bm25", "embedding-cache",
                cache.toString(), "maxOutputTokens", "64", "maxTurns", "5"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static Arm arm(String kind, Path cache, String... kv) {
        return Arms.create(kind, opts(cache, kv), EST);
    }

    /** Answers by turn: 1 → the first step, 2 → the second, …; turn = (conversation size + 1) / 2. */
    static ScriptedModelClient scripted(String provider, Function<Integer, ModelResponse> byTurn) {
        return new ScriptedModelClient(provider, "m", req -> byTurn.apply((req.conversation().size() + 1) / 2));
    }

    static String toolResultText(ModelRequest req) {
        Turn.ToolResults r = (Turn.ToolResults) req.conversation().get(req.conversation().size() - 1);
        return r.results().get(0).content();
    }

    @Test
    void genericSearchDescribesThenCallsAndCountsDiscoveryCalls(@TempDir Path cache) {
        ScriptedModelClient fake = scripted("fake", turn -> switch (turn) {
            case 1 -> ScriptedModelClient.callTool(DiscoveryTools.SEARCH, Map.of("query", "geocode a place"),
                    new Usage(100, 10, 0, 0));
            case 2 -> ScriptedModelClient.callTool(DiscoveryTools.DESCRIBE, Map.of("names", List.of("test.geocode")),
                    new Usage(200, 10, 0, 0));
            default -> ScriptedModelClient.callTool(DiscoveryTools.CALL,
                    Map.of("name", "test.geocode", "arguments", Map.of("q", "Paris")), new Usage(300, 10, 0, 0));
        });
        Arm arm = arm("generic-search", cache);
        ArmResult r = arm.run(TASK, CATALOG, fake);

        assertThat(arm.name()).isEqualTo("generic-search(catalog-bm25,inline=0,fallback=progressive)");
        assertThat(fake.requests()).hasSize(3).allSatisfy(req -> assertThat(req.tools()).extracting(ToolSpec::name)
                .containsExactly(DiscoveryTools.SEARCH, DiscoveryTools.DESCRIBE, DiscoveryTools.CALL));
        assertThat(fake.requests().get(0).system()).startsWith(Prompts.SYSTEM).contains(DiscoveryTools.SEARCH);
        assertThat(toolResultText(fake.requests().get(1))).contains("test.geocode").contains("confidence:")
                .doesNotContain("input_schema");
        assertThat(toolResultText(fake.requests().get(2))).contains("input_schema");
        assertThat(r.discoveryCalls()).isEqualTo(2);
        assertThat(r.modelCalls()).isEqualTo(3);
        assertThat(r.calls()).singleElement().satisfies(c -> {
            assertThat(c.toolId()).isEqualTo("g:geo");
            assertThat(c.name()).isEqualTo("test.geocode");
            assertThat(c.arguments()).containsEntry("q", "Paris");
        });
        assertThat(r.usage()).isEqualTo(new Usage(600, 30, 0, 0));
        assertThat(r.toolsOffered()).isEqualTo(3);
        assertThat(r.toolPayloadTokens()).isPositive();
        assertThat(r.extras()).containsEntry("goldFound", true).containsEntry("described", List.of("g:geo"));
        assertThat((List<?>) r.extras().get("searches")).hasSize(1);
    }

    @Test
    void inlinedSchemasSkipTheDescribeStep(@TempDir Path cache) {
        ScriptedModelClient fake = scripted("fake", turn -> turn == 1
                ? ScriptedModelClient.callTool(DiscoveryTools.SEARCH, Map.of("query", "geocode"), Usage.ZERO)
                : ScriptedModelClient.callTool(DiscoveryTools.CALL, Map.of("name", "test.geocode",
                "arguments", "{\"q\":\"Paris\"}"), Usage.ZERO));
        Arm arm = arm("generic-search", cache, "inlineSchemas", "3", "fallback", "none");
        ArmResult r = arm.run(TASK, CATALOG, fake);
        assertThat(arm.name()).isEqualTo("generic-search(catalog-bm25,inline=3,fallback=none)");
        assertThat(toolResultText(fake.requests().get(1))).contains("test.geocode").contains("input_schema");
        assertThat(r.discoveryCalls()).isEqualTo(1);
        assertThat(r.calls()).singleElement().satisfies(c -> {
            assertThat(c.toolId()).isEqualTo("g:geo");
            assertThat(c.arguments()).containsEntry("q", "Paris"); // arguments given as a JSON string
            assertThat(c.argumentError()).isNull();
        });
    }

    @Test
    void loadedSchemasBecomeCallableAndDirectCallsAreSelections(@TempDir Path cache) {
        ScriptedModelClient fake = scripted("fake", turn -> turn == 1
                ? ScriptedModelClient.callTool(DiscoveryTools.DESCRIBE, Map.of("names", List.of("test.geocode")), Usage.ZERO)
                : ScriptedModelClient.callTool("test__geocode", Map.of("q", "Paris"), Usage.ZERO));
        ArmResult r = arm("generic-search", cache, "load", "true").run(TASK, CATALOG, fake);
        assertThat(fake.requests().get(1).tools()).extracting(ToolSpec::name).contains("test__geocode");
        assertThat(r.calls()).extracting(SelectedCall::toolId).containsExactly("g:geo");
        assertThat(r.discoveryCalls()).isEqualTo(1);
    }

    @Test
    void invalidDiscoveryCallsAreAnsweredWithErrors(@TempDir Path cache) {
        ScriptedModelClient fake = scripted("fake", turn -> turn == 1
                ? ScriptedModelClient.callTool(DiscoveryTools.SEARCH, Map.of("query", ""), Usage.ZERO)
                : ScriptedModelClient.callTool(DiscoveryTools.CALL, Map.of("name", "nope.nothing"), Usage.ZERO));
        ArmResult r = arm("generic-search", cache).run(TASK, CATALOG, fake);
        Turn.ToolResults results = (Turn.ToolResults) fake.requests().get(1).conversation().get(2);
        assertThat(results.results().get(0).error()).isTrue();
        assertThat(r.calls()).singleElement().satisfies(c -> assertThat(c.toolId()).isNull());
    }

    @Test
    void nativeArmsRefuseModelsOfTheOtherProvider(@TempDir Path cache) {
        ScriptedModelClient fake = scripted("openai", turn -> ScriptedModelClient.text("hi", Usage.ZERO));
        ArmResult r = arm("native-anthropic", cache).run(TASK, CATALOG, fake);
        assertThat(r.error()).contains("needs a anthropic model");
        assertThat(fake.requests()).isEmpty();
        assertThat(arm("native-openai", cache).run(TASK, CATALOG, scripted("anthropic",
                turn -> ScriptedModelClient.text("hi", Usage.ZERO))).error()).contains("openai");
        assertThat(Arms.available()).contains("generic-search", "native-anthropic", "native-openai");
    }

    @Test
    void costGuardIsCheckedBeforeEveryTurnOfAMultiTurnArm(@TempDir Path cache) {
        PriceTable prices = PriceTable.empty().put("fake:m", new PriceTable.Price(1, 10, 0.1, 1.25));
        CostGuard guard = new CostGuard(prices, 1.5, 0, SpendLedger.inMemory(), 1.3, 0, false);
        // Turn 1 is affordable; its reported usage ($1.499) leaves too little for turn 2's worst case.
        ScriptedModelClient fake = scripted("fake", turn -> ScriptedModelClient.callTool(DiscoveryTools.SEARCH,
                Map.of("query", "geocode"), new Usage(1_499_000, 0, 0, 0)));
        Arm arm = arm("generic-search", cache);
        assertThatThrownBy(() -> arm.run(TASK, CATALOG, new GuardedModelClient(fake, guard, EST)))
                .isInstanceOf(BudgetExceededException.class).hasMessageContaining("global budget");
        assertThat(fake.requests()).hasSize(1);
        assertThat(guard.runCalls()).isEqualTo(1);
    }

    @Test
    void perTurnEstimatesCountRawToolsAndWhatRawResultsExpandTo() {
        Map<String, Object> deferred = Map.of("name", "geocode", "description", "Find coordinates",
                "input_schema", Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string",
                        "description", "a long description of the query parameter that only matters once loaded"))),
                "defer_loading", true);
        Map<String, Object> eager = new LinkedHashMap<>(deferred);
        eager.remove("defer_loading");
        List<Turn> conv = List.of(new Turn.User("hi"));
        long none = EST.request(new ModelRequest(null, conv, List.of(), 1));
        long withDeferred = EST.request(new ModelRequest(null, conv, List.of(), 1, List.of(deferred)));
        long withEager = EST.request(new ModelRequest(null, conv, List.of(), 1, List.of(eager)));
        assertThat(withDeferred).isGreaterThan(none).isLessThan(withEager);
        assertThat(EST.rawTools(List.of(Map.of("type", "namespace", "name", "crm", "description", "CRM",
                "tools", List.of(deferred))))).isEqualTo(EST.text("crm") + EST.text("CRM") + EST.rawTools(List.of(deferred)));
        ToolResult expanded = new ToolResult("c", "geocode: Find coordinates {\"q\":\"string\"}", false,
                Map.of("type", "tool_result", "tool_use_id", "c", "content", List.of()));
        long withResult = EST.request(new ModelRequest(null, List.of(new Turn.User("hi"),
                new Turn.Assistant("", List.of(), null), new Turn.ToolResults(List.of(expanded))), List.of(), 1));
        assertThat(withResult - none).isEqualTo(EST.text(expanded.content()));
    }
}
