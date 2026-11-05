package io.agentweave.discovery;

import io.agentweave.core.server.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderPayloadsTest {

    private final CapabilityCatalog catalog = CapabilityCatalog.builder()
            .source("files", Fixtures.files(), SourcePolicy.builder().toolScopes("deleteFile", "files:write").build())
            .source("weather", Fixtures.weather())
            .build();

    @Test
    void anthropicDeferredToolsCoverOnlyPermittedAndAvailableCapabilities() {
        AnthropicPayloads anthropic = new AnthropicPayloads(catalog);
        catalog.markUnavailable("weather", "down");

        List<Map<String, Object>> tools = anthropic.deferredTools(DiscoveryQuery.builder("").build());

        assertThat(tools).extracting(t -> t.get("name")).containsExactly("files__listDirectory", "files__readFile");
        Map<String, Object> read = tools.get(1);
        assertThat(read).containsOnlyKeys("name", "description", "input_schema", "defer_loading")
                .containsEntry("defer_loading", true);
    }

    @Test
    void anthropicCustomSearchReturnsToolReferences() {
        AnthropicPayloads anthropic = new AnthropicPayloads(catalog);
        anthropic.deferredTools(DiscoveryQuery.builder("").build());

        assertThat(anthropic.customSearchTool()).containsEntry("name", "search_capabilities")
                .doesNotContainKey("defer_loading").containsKey("input_schema");
        Map<String, Object> result = anthropic.searchResult("toolu_1", Map.of("query", "weather forecast"),
                CallerIdentity.ANONYMOUS);
        assertThat(result).containsEntry("type", "tool_result").containsEntry("tool_use_id", "toolu_1");
        assertThat((List<?>) result.get("content")).first()
                .isEqualTo(Map.of("type", "tool_reference", "tool_name", "weather__forecast"));
        assertThat(anthropic.catalogName("weather__forecast")).contains("weather.forecast");
    }

    @Test
    void anthropicHostedSearchToolTypes() {
        assertThat(AnthropicPayloads.hostedSearchTool(AnthropicPayloads.HostedSearch.REGEX))
                .isEqualTo(Map.of("type", "tool_search_tool_regex_20251119", "name", "tool_search_tool_regex"));
        assertThat(AnthropicPayloads.hostedSearchTool(AnthropicPayloads.HostedSearch.BM25))
                .isEqualTo(Map.of("type", "tool_search_tool_bm25_20251119", "name", "tool_search_tool_bm25"));
    }

    @Test
    void openAiClientSearchAndOutput() {
        OpenAiPayloads openai = new OpenAiPayloads(catalog);
        assertThat(openai.clientSearchTool()).containsEntry("type", "tool_search").containsEntry("execution", "client")
                .containsKey("parameters");
        assertThat(OpenAiPayloads.hostedSearchTool()).isEqualTo(Map.of("type", "tool_search"));

        Map<String, Object> out = openai.searchOutput("call_1", Map.of("query", "weather forecast"),
                CallerIdentity.ANONYMOUS);
        assertThat(out).containsEntry("type", "tool_search_output").containsEntry("execution", "client")
                .containsEntry("call_id", "call_1").containsEntry("status", "completed");
        Map<String, Object> fn = Fixtures.map(((List<?>) out.get("tools")).get(0));
        assertThat(fn.get("type")).isEqualTo("function");
        assertThat(fn.get("name")).isEqualTo("weather__forecast");
        assertThat(fn).containsKey("parameters").doesNotContainKey("defer_loading");
        assertThat(openai.catalogName("weather__forecast")).contains("weather.forecast");
    }

    @Test
    void openAiNamespacesKeepDeferralOnInnerFunctions() {
        List<Map<String, Object>> grouped = new OpenAiPayloads(catalog)
                .deferredFunctions(DiscoveryQuery.builder("").build(), true);
        assertThat(grouped).extracting(n -> n.get("name")).containsExactly("files", "weather");
        Map<String, Object> files = grouped.get(0);
        assertThat(files).containsEntry("type", "namespace").doesNotContainKey("defer_loading");
        assertThat((List<?>) files.get("tools")).allSatisfy(f -> assertThat(Fixtures.map(f)).containsEntry("defer_loading", true))
                .hasSize(2);
    }

    @Test
    void providerNamesAreReversibleSafeAndCollisionChecked() {
        ProviderToolNames names = new ProviderToolNames();
        assertThat(names.providerName("a.b-c")).isEqualTo("a__b-c");
        assertThat(names.catalogName("a__b-c")).contains("a.b-c");
        String longName = "s." + "x".repeat(100);
        assertThat(names.providerName(longName)).matches(ProviderToolNames.PROVIDER_SAFE);
        assertThat(names.catalogName(names.providerName(longName))).contains(longName);

        names.providerName("a.b");
        assertThatThrownBy(() -> names.providerName("a__b")).isInstanceOf(IllegalStateException.class);
    }
}
