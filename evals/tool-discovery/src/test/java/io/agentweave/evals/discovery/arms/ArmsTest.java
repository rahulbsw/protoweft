package io.agentweave.evals.discovery.arms;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.Fixtures;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.GoldCall;
import io.agentweave.evals.discovery.llm.ModelCallException;
import io.agentweave.evals.discovery.llm.ModelClient;
import io.agentweave.evals.discovery.llm.ModelRequest;
import io.agentweave.evals.discovery.llm.ModelResponse;
import io.agentweave.evals.discovery.llm.ScriptedModelClient;
import io.agentweave.evals.discovery.llm.TokenEstimator;
import io.agentweave.evals.discovery.llm.ToolCall;
import io.agentweave.evals.discovery.llm.ToolResult;
import io.agentweave.evals.discovery.llm.Turn;
import io.agentweave.evals.discovery.llm.Usage;
import io.agentweave.evals.discovery.retrieval.OracleRetriever;
import io.agentweave.evals.discovery.retrieval.RandomRetriever;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArmsTest {

    private static final EvalTool GOLD = Fixtures.tool("g:geo", "geocode");
    private static final EvalCatalog CATALOG = EvalCatalog.builder().required(List.of(GOLD))
            .distractorPool(Fixtures.distractors(30)).size(20).seed(3).build();
    private static final EvalTask TASK = new EvalTask("t1", "test", "Where is Paris?", null, Map.of("g:geo", 1),
            List.of(new GoldCall("g:geo", Map.of("q", "Paris"))), Set.of());
    private static final TokenEstimator EST = TokenEstimator.o200k();

    /** Calls the gold tool if offered, otherwise answers in text. */
    private static ScriptedModelClient goldCaller() {
        return new ScriptedModelClient("fake", "m", req -> req.tools().stream().anyMatch(t -> t.name().equals("geocode"))
                ? ScriptedModelClient.callTool("geocode", Map.of("q", "Paris"), new Usage(500, 20, 100, 0))
                : ScriptedModelClient.text("no tool fits", new Usage(50, 5, 0, 0)));
    }

    @Test
    void allToolsSendsEveryProviderSchemaInOneRequest() {
        ScriptedModelClient fake = goldCaller();
        ArmResult r = new AllToolsArm(256, EST).run(TASK, CATALOG, fake);
        assertThat(fake.requests()).hasSize(1);
        ModelRequest req = fake.requests().get(0);
        assertThat(req.tools()).hasSize(20).isEqualTo(CATALOG.providerSpecs());
        assertThat(req.system()).isEqualTo(Prompts.SYSTEM);
        assertThat(req.maxOutputTokens()).isEqualTo(256);
        assertThat(((Turn.User) req.conversation().get(0)).text()).isEqualTo("Where is Paris?");
        assertThat(r.arm()).isEqualTo("all-tools");
        assertThat(r.calls()).extracting(SelectedCall::toolId).containsExactly("g:geo");
        assertThat(r.calls().get(0).arguments()).containsEntry("q", "Paris");
        assertThat(r.toolsOffered()).isEqualTo(20);
        assertThat(r.toolPayloadTokens()).isEqualTo(EST.tools(CATALOG.providerSpecs()));
        assertThat(r.usage()).isEqualTo(new Usage(500, 20, 100, 0));
        assertThat(r.modelCalls()).isEqualTo(1);
        assertThat(r.discoveryCalls()).isZero();
    }

    @Test
    void unknownToolNamesMapToNull() {
        ScriptedModelClient fake = new ScriptedModelClient("fake", "m",
                req -> ScriptedModelClient.callTool("made_up", Map.of(), Usage.ZERO));
        ArmResult r = new AllToolsArm(64, EST).run(TASK, CATALOG, fake);
        assertThat(r.calls()).singleElement().satisfies(c -> {
            assertThat(c.toolId()).isNull();
            assertThat(c.name()).isEqualTo("made_up");
        });
    }

    @Test
    void topKInjectsOnlyRetrievedSchemas() {
        ScriptedModelClient fake = goldCaller();
        TopKInjectedArm arm = new TopKInjectedArm(new OracleRetriever(), 3, 64, EST);
        ArmResult r = arm.run(TASK, CATALOG, fake);
        assertThat(arm.name()).isEqualTo("top-k(k=3,oracle)");
        assertThat(fake.requests().get(0).tools()).hasSize(3);
        assertThat(fake.requests().get(0).tools().get(0).name()).isEqualTo("geocode");
        assertThat(r.calls()).extracting(SelectedCall::toolId).containsExactly("g:geo");
        assertThat(r.extras()).containsEntry("goldInjected", true);
        assertThat(r.toolsOffered()).isEqualTo(3);
    }

    @Test
    void topKWithAMissedGoldToolLeadsToNoCall() {
        ScriptedModelClient fake = goldCaller();
        EvalCatalog big = EvalCatalog.builder().required(List.of(GOLD)).distractorPool(Fixtures.distractors(500))
                .size(400).seed(3).build();
        ArmResult r = new TopKInjectedArm(new RandomRetriever(1), 1, 64, EST).run(TASK, big, fake);
        assertThat(r.extras()).containsEntry("goldInjected", false);
        assertThat(r.calls()).isEmpty();
        assertThat(r.finalText()).isEqualTo("no tool fits");
    }

    @Test
    void modelErrorsAreRecordedNotThrown() {
        ModelClient failing = new ScriptedModelClient("fake", "m", req -> {
            throw new ModelCallException("boom", 400, null);
        });
        ArmResult r = new AllToolsArm(64, EST).run(TASK, CATALOG, failing);
        assertThat(r.error()).isEqualTo("boom");
        assertThat(r.calls()).isEmpty();
        assertThat(r.modelCalls()).isZero();
    }

    /**
     * A multi-turn arm in the shape the generic search-tool arm will take: the model only has a
     * search tool at first, the arm answers searches with results and loads the matching schemas,
     * then the model calls the real tool. Discovery calls are counted.
     */
    @Test
    void toolLoopSupportsSearchThenCall() {
        ToolSpec search = new ToolSpec("search_tools", "Find tools", Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string"))));
        List<String> seenTools = new ArrayList<>();
        ScriptedModelClient fake = new ScriptedModelClient("fake", "m", req -> {
            seenTools.add(req.tools().stream().map(ToolSpec::name).toList().toString());
            boolean loaded = req.tools().stream().anyMatch(t -> t.name().equals("geocode"));
            return loaded ? ScriptedModelClient.callTool("geocode", Map.of("q", "Paris"), new Usage(300, 10, 0, 0))
                    : ScriptedModelClient.callTool("search_tools", Map.of("query", "geocode"), new Usage(100, 10, 0, 0));
        });
        ToolLoop.Outcome o = ToolLoop.run(fake, Prompts.SYSTEM, TASK.query(), List.of(search), 64, 5,
                (ToolCall call, int turn) -> call.name().equals("search_tools")
                        ? ToolLoop.Decision.respond(new ToolResult(call.id(), "geocode: find coordinates", false),
                        List.of(CATALOG.providerSpec("g:geo")), true)
                        : ToolLoop.Decision.select(), EST);
        ArmResult r = ToolLoop.toResult("search-tool", TASK.id(), o, CATALOG, Map.of());
        assertThat(r.discoveryCalls()).isEqualTo(1);
        assertThat(r.modelCalls()).isEqualTo(2);
        assertThat(r.calls()).extracting(SelectedCall::toolId).containsExactly("g:geo");
        assertThat(r.usage()).isEqualTo(new Usage(400, 20, 0, 0));
        assertThat(seenTools).containsExactly("[search_tools]", "[search_tools, geocode]");
        // The second request replays the assistant turn and the tool result.
        List<Turn> conv = fake.requests().get(1).conversation();
        assertThat(conv).hasSize(3);
        assertThat(conv.get(2)).isInstanceOf(Turn.ToolResults.class);
    }

    @Test
    void toolLoopStopsAtMaxTurns() {
        ModelClient alwaysSearch = new ScriptedModelClient("fake", "m",
                req -> ScriptedModelClient.callTool("search_tools", Map.of(), Usage.ZERO));
        ToolLoop.Outcome o = ToolLoop.run(alwaysSearch, null, "q", List.of(), 64, 3,
                (call, turn) -> ToolLoop.Decision.respond(new ToolResult(call.id(), "nothing", false), null, true), null);
        assertThat(o.hitMaxTurns()).isTrue();
        assertThat(o.discoveryCalls()).isEqualTo(3);
        assertThat(o.responses()).hasSize(3);
        assertThat(o.realCalls()).isEmpty();
        assertThatThrownBy(() -> ToolLoop.run(alwaysSearch, null, "q", List.of(), 64, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void armSpecsResolve() {
        assertThat(Arms.create("all-tools", Map.of(), EST).name()).isEqualTo("all-tools");
        assertThat(Arms.create("top-k:k=7,retriever=random", Map.of("seed", "1"), EST).name())
                .isEqualTo("top-k(k=7,random)");
        assertThatThrownBy(() -> Arms.create("native", Map.of(), EST)).hasMessageContaining("unknown arm");
        assertThatThrownBy(() -> Arms.create("top-k:k", Map.of(), EST)).hasMessageContaining("key=value");
        assertThat(Arms.available()).contains("all-tools", "top-k");
    }

    @Test
    void responsesAreNeverNullTurns() {
        assertThatThrownBy(() -> new ModelResponse(null, Usage.ZERO, 0, null)).isInstanceOf(NullPointerException.class);
    }
}
