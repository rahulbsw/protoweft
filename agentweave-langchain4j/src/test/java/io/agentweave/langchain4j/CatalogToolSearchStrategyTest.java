package io.agentweave.langchain4j;

import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.LlmVisibleToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.search.ToolSearchRequest;
import dev.langchain4j.service.tool.search.ToolSearchResult;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.SourcePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogToolSearchStrategyTest {

    interface Assistant {
        String chat(String message);
    }

    private static final String SEARCH = CatalogToolSearchStrategy.DEFAULT_TOOL_NAME;
    private static final Map<String, Object> TWO_INTS = Map.of("type", "object",
            "properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
            "required", List.of("a", "b"));
    private static final Map<String, Object> ONE_STRING = Map.of("type", "object",
            "properties", Map.of("value", Map.of("type", "string")), "required", List.of("value"));

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private CapabilityCatalog catalog(FallbackPolicy fallback) {
        LocalTools math = LocalTools.builder()
                .add(new LocalTools.LocalTool(new ToolSpec("add", "Adds two integers", TWO_INTS,
                        Map.of(ToolSpec.META_READ_ONLY, true)), (args, caller) -> {
                    calls.add("add");
                    return ToolOutcome.text(String.valueOf(((Number) args.get("a")).intValue()
                            + ((Number) args.get("b")).intValue()));
                }))
                .tool("multiply", "Multiplies two integers", TWO_INTS, (args, caller) -> {
                    calls.add("multiply");
                    return ToolOutcome.text("product");
                })
                .build();
        LocalTools text = LocalTools.builder()
                .tool("uppercase", "Converts text to upper case", ONE_STRING,
                        (args, caller) -> ToolOutcome.text(String.valueOf(args.get("value")).toUpperCase()))
                .build();
        LocalTools vault = LocalTools.builder()
                .tool("read_secret", "Reads a secret from the vault", ONE_STRING, (args, caller) -> {
                    calls.add("read_secret by " + caller.name());
                    return ToolOutcome.text("s3cr3t");
                })
                .build();
        return CapabilityCatalog.builder()
                .source("math", math)
                .source("text", text)
                .source("vault", vault, SourcePolicy.builder().requiredScopes("secrets:read").build())
                .fallback(fallback)
                .build();
    }

    private static Assistant assistant(ScriptedToolCallsModel model, CatalogToolSearchStrategy strategy) {
        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(strategy.toolProvider())
                .toolSearchStrategy(strategy)
                .build();
    }

    @Test
    void modelSearchesThenCallsTheFoundCatalogTool() {
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.from(catalog(FallbackPolicy.DEFAULT));
        ScriptedToolCallsModel model = new ScriptedToolCallsModel()
                .call(SEARCH, "{\"query\": \"add two integers\"}")
                .call("math__add", "{\"a\": 2, \"b\": 3}")
                .answer();

        String answer = assistant(model, strategy).chat("What is 2 + 3?");

        assertThat(answer).isEqualTo("The answer is 5");
        assertThat(calls).containsExactly("add");
        // Only the search tool is sent before a search: no catalog tool, searched or not.
        assertThat(model.toolsOffered(0)).containsExactly(SEARCH);
        assertThat(model.toolResultIn(1).text()).contains("math__add").contains("confidence:");
        assertThat(model.toolsOffered(1)).contains(SEARCH, "math__add").doesNotContain("vault__read_secret");
        ToolSpecification add = model.requests.get(1).toolSpecifications().stream()
                .filter(s -> s.name().equals("math__add")).findFirst().orElseThrow();
        assertThat(add.description()).isEqualTo("Adds two integers");
        assertThat(add.parameters().required()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void foundToolsStayLoadedAcrossInvocationsThroughChatMemory() {
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.from(catalog(FallbackPolicy.DEFAULT));
        ScriptedToolCallsModel model = new ScriptedToolCallsModel()
                .call(SEARCH, "{\"query\": \"upper case text\"}").answer()
                .call("text__uppercase", "{\"value\": \"hi\"}").answer();
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .toolProvider(strategy.toolProvider())
                .toolSearchStrategy(strategy)
                .build();

        assistant.chat("Find a tool to shout");
        String answer = assistant.chat("Shout hi");

        assertThat(answer).isEqualTo("The answer is HI");
        assertThat(model.toolsOffered(0)).containsExactly(SEARCH);
        assertThat(model.toolsOffered(2)).containsExactlyInAnyOrder(SEARCH, "text__uppercase");
    }

    @Test
    void scopedCapabilityIsFoundOnlyForACallerHoldingTheScope() {
        AtomicReference<CallerIdentity> caller = new AtomicReference<>(CallerIdentity.of("guest"));
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.builder(catalog(FallbackPolicy.of(FallbackPolicy.Mode.NONE)))
                .callerIdentity(caller::get).build();

        ScriptedToolCallsModel guestModel = new ScriptedToolCallsModel()
                .call(SEARCH, "{\"query\": \"read secret from the vault\"}").answer();
        assistant(guestModel, strategy).chat("Get the secret");
        assertThat(guestModel.toolsOffered(1)).doesNotContain("vault__read_secret");
        assertThat(guestModel.toolResultIn(1).text()).doesNotContain("vault");

        caller.set(new CallerIdentity("admin", Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("secrets:read"))));
        ScriptedToolCallsModel adminModel = new ScriptedToolCallsModel()
                .call(SEARCH, "{\"query\": \"read secret from the vault\"}")
                .call("vault__read_secret", "{\"value\": \"db\"}")
                .answer();
        String answer = assistant(adminModel, strategy).chat("Get the secret");

        assertThat(answer).isEqualTo("The answer is s3cr3t");
        assertThat(calls).containsExactly("read_secret by admin");
    }

    @Test
    void alwaysVisibleCapabilitiesAreSentWithoutASearch() {
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.builder(catalog(FallbackPolicy.DEFAULT))
                .alwaysVisible(spec -> spec.name().equals("math.multiply")).build();
        ScriptedToolCallsModel model = new ScriptedToolCallsModel().answer();

        assistant(model, strategy).chat("hi");

        assertThat(model.toolsOffered(0)).containsExactlyInAnyOrder(SEARCH, "math__multiply");
        AiServiceTool multiply = strategy.toolProvider().provideTools(providerRequest()).aiServiceTools().stream()
                .filter(t -> t.name().equals("math__multiply")).findFirst().orElseThrow();
        assertThat(multiply.toolSpecification().metadata())
                .containsEntry(ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.ALWAYS_VISIBLE);
    }

    @Test
    void searchHonorsTheModelsLimitAndIgnoresOtherFilters() {
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.from(catalog(FallbackPolicy.of(FallbackPolicy.Mode.NONE)));

        assertThat(search(strategy, "{\"query\": \"integers\"}").foundToolNames())
                .containsExactlyInAnyOrder("math__add", "math__multiply");
        assertThat(search(strategy, "{\"query\": \"integers\", \"read_only\": true, \"sources\": [\"text\"]}")
                .foundToolNames()).containsExactlyInAnyOrder("math__add", "math__multiply");
        assertThat(search(strategy, "{\"query\": \"integers\", \"limit\": 1}").foundToolNames()).hasSize(1);
    }

    @Test
    void lowConfidenceReportsTheFallbackStageAndListsCandidatesWithoutLoadingThem() {
        CatalogToolSearchStrategy progressive = CatalogToolSearchStrategy.from(catalog(FallbackPolicy.DEFAULT));
        ToolSearchResult names = search(progressive, "{\"query\": \"weather forecast\"}");
        assertThat(names.foundToolNames()).isEmpty();
        assertThat(names.toolResultMessageText()).contains("fallback: NAMES_ONLY")
                .contains("Other candidates").contains("math__add").contains("confidence: LOW");

        CatalogToolSearchStrategy all = CatalogToolSearchStrategy.from(catalog(FallbackPolicy.of(FallbackPolicy.Mode.ALL_SCHEMAS)));
        ToolSearchResult baseline = search(all, "{\"query\": \"weather forecast\"}");
        assertThat(baseline.foundToolNames()).containsExactlyInAnyOrder("math__add", "math__multiply", "text__uppercase");
        assertThat(baseline.toolResultMessageText()).contains("fallback: ALL_SCHEMAS");
    }

    @Test
    void unavailableSourcesAreReportedAndAlreadyFoundToolsAreNotFoundAgain() {
        CapabilityCatalog catalog = catalog(FallbackPolicy.of(FallbackPolicy.Mode.NONE));
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.from(catalog);
        catalog.markUnavailable("text", "maintenance");

        ToolSearchResult hidden = search(strategy, "{\"query\": \"upper case text\"}");
        assertThat(hidden.foundToolNames()).isEmpty();
        assertThat(hidden.toolResultMessageText()).contains("hidden as unavailable: {text=1}");

        List<ToolSpecification> withoutAdd = searchable(strategy).stream()
                .filter(s -> !s.name().equals("math__add")).toList();
        ToolSearchResult again = strategy.search(request("{\"query\": \"add integers\"}", withoutAdd));
        assertThat(again.foundToolNames()).doesNotContain("math__add");
        assertThat(again.toolResultMessageText()).contains("Already available: math__add");
    }

    @Test
    void invalidArgumentsAreReportedToTheModel() {
        CatalogToolSearchStrategy strategy = CatalogToolSearchStrategy.from(catalog(FallbackPolicy.DEFAULT));

        assertThatThrownBy(() -> search(strategy, "{\"limit\": 3}"))
                .isInstanceOf(LlmVisibleToolExecutionException.class).hasMessageContaining("'query'");
        assertThatThrownBy(() -> search(strategy, "[1]"))
                .isInstanceOf(LlmVisibleToolExecutionException.class).hasMessageContaining("not a JSON object");
        assertThat(strategy.getToolSearchTools(InvocationContext.builder().build()))
                .singleElement().satisfies(t -> {
                    assertThat(t.name()).isEqualTo(SEARCH);
                    assertThat(t.parameters().required()).containsExactly("query");
                });
    }

    private static ToolSearchResult search(CatalogToolSearchStrategy strategy, String arguments) {
        return strategy.search(request(arguments, searchable(strategy)));
    }

    private static List<ToolSpecification> searchable(CatalogToolSearchStrategy strategy) {
        return strategy.toolProvider().provideTools(providerRequest()).aiServiceTools().stream()
                .map(AiServiceTool::toolSpecification).toList();
    }

    private static ToolSearchRequest request(String arguments, List<ToolSpecification> searchable) {
        return ToolSearchRequest.builder()
                .toolExecutionRequest(ToolExecutionRequest.builder().id("s").name(SEARCH).arguments(arguments).build())
                .searchableTools(searchable)
                .invocationContext(InvocationContext.builder().build())
                .build();
    }

    private static ToolProviderRequest providerRequest() {
        return new ToolProviderRequest("memory", UserMessage.from("hi"));
    }
}
