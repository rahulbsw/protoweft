package io.agentweave.discovery.anthropic;

import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.discovery.AnthropicPayloads;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoverySession;
import io.agentweave.discovery.ProviderToolNames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.agentweave.discovery.anthropic.TestCatalogs.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicDiscoveryTest {

    private final TestCatalogs.FixedSource files = TestCatalogs.files();
    private final TestCatalogs.FixedSource crm = TestCatalogs.crm();
    private final CapabilityCatalog catalog = TestCatalogs.catalog(files, crm, TestCatalogs.weather());
    private final DiscoveryQuery all = DiscoveryQuery.builder("").build();

    static ToolUseBlock toolUse(String id, String name, String inputJson) {
        return TestCatalogs.parse("{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"input\":" + inputJson + "}", ToolUseBlock.class);
    }

    private static List<Tool> customTools(List<ToolUnion> tools) {
        return tools.stream().filter(ToolUnion::isTool).map(ToolUnion::asTool).toList();
    }

    // ------------------------------------------------------------------ request parts

    @Test
    void typedToolsSerializeExactlyLikeTheRawPayloads() {
        AnthropicPayloads raw = new AnthropicPayloads(catalog);
        List<Object> expected = new ArrayList<>();
        expected.add(raw.customSearchTool());
        expected.addAll(raw.deferredTools(all));

        List<ToolUnion> typed = AnthropicDiscovery.of(catalog).tools(all);

        assertThat(json(typed)).isEqualTo(json(expected));
        // The rich schema (enum, numbers, nested object, additionalProperties, $schema) survives.
        JsonNode find = json(typed).get(2);
        assertThat(find.get("name").asText()).isEqualTo("crm__findCustomer");
        assertThat(find.at("/input_schema/additionalProperties").asBoolean(true)).isFalse();
        assertThat(find.at("/input_schema/properties/score/default").asDouble()).isEqualTo(2.5);
    }

    @Test
    void capabilitiesAreDeferredAndTheSearchToolIsNot() {
        List<Tool> tools = customTools(AnthropicDiscovery.of(catalog).tools(all));

        Tool search = tools.get(0);
        assertThat(search.name()).isEqualTo("search_capabilities");
        assertThat(search.deferLoading()).isEmpty();
        assertThat(search.inputSchema().required()).contains(List.of("query"));
        assertThat(tools.subList(1, tools.size())).isNotEmpty().allSatisfy(t -> {
            assertThat(t.deferLoading()).contains(true);
            assertThat(t.cacheControl()).isEmpty();
        });
        assertThat(tools).extracting(Tool::name).containsExactly("search_capabilities",
                "crm__failingSync", "crm__findCustomer", "files__listDirectory", "files__readFile",
                "weather__forecast");
    }

    @Test
    void emptySchemaBecomesAnEmptyObjectSchema() {
        Tool failing = customTools(AnthropicDiscovery.of(catalog).tools(all)).get(1);
        assertThat(json(failing.inputSchema())).isEqualTo(json(Map.of("type", "object", "properties", Map.of())));
    }

    @Test
    void singleToolConversionKeepsOddSchemasAsTheRawMapHasThem() {
        AnthropicDiscovery discovery = AnthropicDiscovery.of(catalog);
        AnthropicPayloads raw = new AnthropicPayloads(catalog, discovery.names());
        var spec = new io.agentweave.core.tool.ToolSpec("src.odd", "No type keyword.", Map.of(
                "properties", Map.of("n", Map.of("type", "integer")), "required", "n"));
        for (boolean deferred : new boolean[] {true, false}) {
            assertThat(json(discovery.tool(spec, deferred))).isEqualTo(json(raw.tool(spec, deferred)));
        }
        assertThat(json(discovery.tool(spec, false)).get("input_schema").has("type")).isFalse();
    }

    @Test
    void permissionsAndAvailabilityFilterTheTools() {
        AnthropicDiscovery admin = AnthropicDiscovery.builder(catalog).caller(TestCatalogs.ADMIN).build();
        assertThat(customTools(admin.tools())).extracting(Tool::name).contains("files__deleteFile");
        // The instance's caller decides, whatever caller the filter carries.
        assertThat(customTools(admin.tools(all))).extracting(Tool::name).contains("files__deleteFile");
        assertThat(customTools(AnthropicDiscovery.of(catalog).tools(DiscoveryQuery.builder("")
                .caller(TestCatalogs.ADMIN).build()))).extracting(Tool::name).doesNotContain("files__deleteFile");

        catalog.markUnavailable("weather", "maintenance");
        assertThat(customTools(AnthropicDiscovery.of(catalog).tools())).extracting(Tool::name)
                .doesNotContain("files__deleteFile", "weather__forecast");
    }

    @Test
    void hostedVariantsSendAnthropicsSearchToolInsteadOfOurs() {
        AnthropicPayloads raw = new AnthropicPayloads(catalog);
        for (var c : List.of(
                Map.entry(AnthropicDiscovery.Mode.HOSTED_REGEX, AnthropicPayloads.HostedSearch.REGEX),
                Map.entry(AnthropicDiscovery.Mode.HOSTED_BM25, AnthropicPayloads.HostedSearch.BM25))) {
            List<ToolUnion> tools = AnthropicDiscovery.builder(catalog).mode(c.getKey()).build().tools(all);

            List<Object> expected = new ArrayList<>();
            expected.add(AnthropicPayloads.hostedSearchTool(c.getValue()));
            expected.addAll(raw.deferredTools(all));
            assertThat(json(tools)).isEqualTo(json(expected));
            assertThat(tools.get(0).isTool()).isFalse();
            assertThat(customTools(tools)).extracting(Tool::name).doesNotContain("search_capabilities");
        }
        assertThat(AnthropicDiscovery.hostedSearchTool(AnthropicDiscovery.Mode.HOSTED_REGEX)
                .isSearchToolRegex20251119()).isTrue();
        assertThatThrownBy(() -> AnthropicDiscovery.hostedSearchTool(AnthropicDiscovery.Mode.CATALOG_SEARCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionSelectionsCanBeSentNonDeferred() {
        DiscoverySession session = new DiscoverySession(catalog);
        AnthropicDiscovery discovery = AnthropicDiscovery.builder(catalog).session(session)
                .keepSelectedLoaded(true).build();
        discovery.tools(all);
        discovery.handle(toolUse("toolu_1", "search_capabilities", "{\"query\":\"weather forecast for a city\"}"));
        assertThat(session.loaded()).contains("weather.forecast");

        List<Tool> tools = customTools(discovery.tools(all));
        assertThat(tools).filteredOn(t -> t.name().equals("weather__forecast")).singleElement()
                .satisfies(t -> assertThat(t.deferLoading()).isEmpty());
        assertThat(tools).filteredOn(t -> t.name().equals("files__readFile")).singleElement()
                .satisfies(t -> assertThat(t.deferLoading()).contains(true));
    }

    // ------------------------------------------------------------------ handle

    @Test
    void searchAnswersWithToolReferencesLikeTheRawPayloads() {
        DiscoverySession session = new DiscoverySession(catalog);
        AnthropicDiscovery discovery = AnthropicDiscovery.builder(catalog).session(session).build();
        discovery.tools(all);
        AnthropicPayloads raw = new AnthropicPayloads(catalog, discovery.names());

        ToolResultBlockParam result = discovery.handle(
                toolUse("toolu_1", "search_capabilities", "{\"query\":\"weather forecast\",\"limit\":2}"));

        assertThat(json(result)).isEqualTo(json(raw.searchResult("toolu_1",
                Map.of("query", "weather forecast", "limit", 2), CallerIdentity.ANONYMOUS)));
        List<ToolResultBlockParam.Content.Block> blocks = result.content().orElseThrow().asBlocks();
        assertThat(blocks.get(0).asToolReference().toolName()).isEqualTo("weather__forecast");
        assertThat(result.isError()).isEmpty();
        assertThat(session.loaded()).contains("weather.forecast");
    }

    @Test
    void searchNeverReferencesForbiddenCapabilities() {
        AnthropicDiscovery discovery = AnthropicDiscovery.of(catalog);
        ToolResultBlockParam result = discovery.handle(
                toolUse("toolu_1", "search_capabilities", "{\"query\":\"delete a file from disk\",\"limit\":10}"));
        assertThat(result.content().orElseThrow().asBlocks())
                .extracting(b -> b.asToolReference().toolName()).doesNotContain("files__deleteFile");
    }

    @Test
    void invalidSearchInputIsAnErrorResult() {
        ToolResultBlockParam result = AnthropicDiscovery.of(catalog)
                .handle(toolUse("toolu_1", "search_capabilities", "{\"query\":\"  \"}"));
        assertThat(result.isError()).contains(true);
        assertThat(result.content().orElseThrow().asString()).contains("query");
    }

    @Test
    void capabilityCallReturnsTheOutcomeText() {
        AnthropicDiscovery discovery = AnthropicDiscovery.of(catalog);
        discovery.tools(all);

        ToolResultBlockParam result = discovery.handle(toolUse("toolu_2", "crm__findCustomer",
                "{\"query\":\"ada@example.com\",\"limit\":3}"));

        assertThat(result.toolUseId()).isEqualTo("toolu_2");
        assertThat(result.content().orElseThrow().asString()).isEqualTo("findCustomer: {limit=3, query=ada@example.com}");
        assertThat(result.isError()).isEmpty();
        assertThat(json(result).get("type").asText()).isEqualTo("tool_result");
    }

    @Test
    void errorOutcomeSetsIsError() {
        AnthropicDiscovery discovery = AnthropicDiscovery.of(catalog);
        discovery.tools(all);

        ToolResultBlockParam result = discovery.handle(toolUse("toolu_3", "crm__failingSync", "{}"));

        assertThat(result.isError()).contains(true);
        assertThat(result.content().orElseThrow().asString()).isEqualTo("sync failed: upstream returned 503");
    }

    @Test
    void nonTextOutcomePartsAreDescribedNotDropped() {
        ToolOutcome outcome = new ToolOutcome(List.of(new Content.Text("chart:"), new Content.Json(Map.of("a", 1)),
                new Content.Data("image/png", "AAAA"), new Content.Link("file:///r.csv", "text/csv", null),
                new Content.Unmapped("resource")), false, null);
        assertThat(AnthropicDiscovery.render(outcome)).isEqualTo("chart:\n{\"a\":1}\n"
                + "[image/png data, 4 base64 chars, not shown]\n[link: file:///r.csv (text/csv)]\n[unmapped resource content]");
        assertThat(AnthropicDiscovery.render(new ToolOutcome(List.of(), false, Map.of("ok", true))))
                .isEqualTo("{\"ok\":true}");
    }

    @Test
    void unknownToolIsAnErrorResult() {
        ToolResultBlockParam result = AnthropicDiscovery.of(catalog).handle(toolUse("toolu_4", "nope", "{}"));
        assertThat(result.isError()).contains(true);
        assertThat(result.content().orElseThrow().asString()).isEqualTo("unknown tool: nope");
    }

    @Test
    void forbiddenCapabilityLooksExactlyLikeAnUnknownOne() {
        ProviderToolNames shared = new ProviderToolNames();
        // An admin's request in the same conversation mapped the name, so it resolves.
        AnthropicDiscovery.builder(catalog).caller(TestCatalogs.ADMIN).names(shared).build().tools();
        AnthropicDiscovery anonymous = AnthropicDiscovery.builder(catalog).names(shared).build();
        assertThat(shared.catalogName("files__deleteFile")).contains("files.deleteFile");

        ToolResultBlockParam forbidden = anonymous.handle(toolUse("toolu_5", "files__deleteFile", "{\"path\":\"/x\"}"));
        ToolResultBlockParam unmapped = AnthropicDiscovery.of(catalog)
                .handle(toolUse("toolu_5", "files__deleteFile", "{\"path\":\"/x\"}"));

        assertThat(json(forbidden)).isEqualTo(json(unmapped));
        assertThat(forbidden.content().orElseThrow().asString()).isEqualTo("unknown tool: files__deleteFile");
        assertThat(files.calls).hasValue(0);
    }

    @Test
    void searchToolNameIsUnknownInHostedMode() {
        ToolResultBlockParam result = AnthropicDiscovery.builder(catalog).mode(AnthropicDiscovery.Mode.HOSTED_BM25)
                .build().handle(toolUse("toolu_6", "search_capabilities", "{\"query\":\"weather\"}"));
        assertThat(result.isError()).contains(true);
    }

    @Test
    void builderRejectsInconsistentSessionAndCaller() {
        DiscoverySession session = new DiscoverySession(catalog);
        assertThatThrownBy(() -> AnthropicDiscovery.builder(catalog).session(session).caller(TestCatalogs.ADMIN).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnthropicDiscovery.builder(TestCatalogs.catalog()).session(session).build())
                .isInstanceOf(IllegalArgumentException.class);
        AnthropicDiscovery fromSession = AnthropicDiscovery.builder(catalog)
                .session(new DiscoverySession(catalog, TestCatalogs.ADMIN, 8)).build();
        assertThat(fromSession.caller()).isEqualTo(TestCatalogs.ADMIN);
    }
}
