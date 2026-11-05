package io.agentweave.discovery.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.Tool;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.OpenAiPayloads;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.agentweave.discovery.openai.Fixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;

/** The typed SDK params serialize to exactly the raw {@link OpenAiPayloads} JSON. */
class WireJsonTest {

    private final CapabilityCatalog catalog = Fixtures.catalog(new Fixtures.FileTools());
    private final OpenAiPayloads raw = new OpenAiPayloads(catalog);
    private final DiscoveryQuery all = DiscoveryQuery.builder("").build();

    @Test
    void clientSearchToolMatchesRawPayload() {
        JsonNode typed = tree(OpenAiDiscovery.of(catalog).clientSearchTool());
        assertThat(typed).isEqualTo(tree(raw.clientSearchTool()));
        assertThat(typed.get("execution").asText()).isEqualTo("client");
    }

    @Test
    void hostedSearchToolIsJustTheType() {
        assertThat(tree(OpenAiDiscovery.hostedSearchTool())).isEqualTo(tree(OpenAiPayloads.hostedSearchTool()))
                .isEqualTo(tree("{\"type\":\"tool_search\"}"));
    }

    @Test
    void deferredFunctionsMatchRawPayload() {
        OpenAiDiscovery typed = OpenAiDiscovery.builder(catalog).mode(OpenAiDiscovery.Mode.HOSTED).build();
        JsonNode functions = tree(typed.deferredTools(all));
        assertThat(functions).isEqualTo(tree(raw.deferredFunctions(all, false)));
        // Anonymous callers never see deleteFile (it needs files:write).
        assertThat(functions.findValuesAsText("name"))
                .containsExactly("files__listDirectory", "files__readFile", "weather__archive", "weather__forecast");
        assertThat(functions).allSatisfy(f -> assertThat(f.get("defer_loading").asBoolean()).isTrue());
        assertThat(functions.get(0).has("strict")).isFalse();
    }

    @Test
    void namespacesMatchRawPayloadWithDeferralOnInnerFunctions() {
        OpenAiDiscovery typed = OpenAiDiscovery.builder(catalog).caller(Fixtures.WRITER)
                .mode(OpenAiDiscovery.Mode.HOSTED).namespaces(true).build();
        DiscoveryQuery writer = DiscoveryQuery.builder("").caller(Fixtures.WRITER).build();

        List<Tool> grouped = typed.deferredTools(writer);
        assertThat(tree(grouped)).isEqualTo(tree(raw.deferredFunctions(writer, true)));

        assertThat(grouped).allSatisfy(t -> assertThat(t.isNamespace()).isTrue());
        assertThat(grouped).extracting(t -> t.asNamespace().name()).containsExactly("files", "weather");
        JsonNode files = tree(grouped.get(0));
        assertThat(files.get("type").asText()).isEqualTo("namespace");
        assertThat(files.get("description").asText()).isEqualTo("Tools from files");
        assertThat(files.has("defer_loading")).isFalse();
        assertThat(files.get("tools")).hasSize(3).allSatisfy(f -> {
            assertThat(f.get("type").asText()).isEqualTo("function");
            assertThat(f.get("defer_loading").asBoolean()).isTrue();
            assertThat(f.has("parameters")).isTrue();
        });
        assertThat(grouped.get(0).asNamespace().tools()).allSatisfy(
                t -> assertThat(t.asFunction().deferLoading()).contains(true));
    }

    @Test
    void hostedToolsAreSearchPlusDeferredFunctions() {
        catalog.markUnavailable("weather", "maintenance");
        List<Tool> tools = OpenAiDiscovery.builder(catalog).mode(OpenAiDiscovery.Mode.HOSTED).build().tools();

        assertThat(tools.get(0).isSearch()).isTrue();
        assertThat(tree(tools.get(0))).isEqualTo(tree(OpenAiPayloads.hostedSearchTool()));
        assertThat(tree(tools.subList(1, tools.size()))).isEqualTo(tree(raw.deferredFunctions(all, false)));
        assertThat(tools).hasSize(3); // listDirectory, readFile; the unavailable weather source is left out
    }

    @Test
    void toolSearchOutputMatchesRawPayload() {
        OpenAiDiscovery typed = OpenAiDiscovery.of(catalog);
        List<String> names = List.of("weather.forecast", "files.readFile");
        assertThat(tree(typed.toolSearchOutput("call_1", names))).isEqualTo(tree(raw.toolSearchOutput("call_1", names)));

        Map<String, Object> args = Map.of("query", "weather forecast for a city");
        JsonNode typedSearch = tree(typed.searchOutput("call_2", args));
        assertThat(typedSearch).isEqualTo(tree(raw.searchOutput("call_2", args, CallerIdentity.ANONYMOUS)));
        assertThat(typedSearch.get("type").asText()).isEqualTo("tool_search_output");
        assertThat(typedSearch.get("tools").get(0).get("name").asText()).isEqualTo("weather__forecast");
        assertThat(typedSearch.get("tools").get(0).has("defer_loading")).isFalse();
    }

    @Test
    void strictIsOmittedByDefaultAndSentWhenConfigured() {
        OpenAiDiscovery lax = OpenAiDiscovery.builder(catalog).strict(false).build();
        JsonNode fn = tree(lax.function(catalog.schema("weather.forecast").orElseThrow(), false));
        assertThat(fn.get("strict").asBoolean(true)).isFalse();
    }

    @Test
    void paramsBuildIntoAResponseCreateRequest() {
        OpenAiDiscovery discovery = OpenAiDiscovery.of(catalog);
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model("gpt-5.4")
                .tools(discovery.tools())
                .inputOfResponse(List.of(ResponseInputItem.ofToolSearchOutput(
                        discovery.toolSearchOutput("call_1", List.of("weather.forecast")))))
                .build();

        JsonNode body = tree(params._body());
        assertThat(body.get("tools")).hasSize(1);
        assertThat(body.get("tools").get(0)).isEqualTo(tree(raw.clientSearchTool()));
        assertThat(body.get("input").get(0)).isEqualTo(tree(raw.toolSearchOutput("call_1", List.of("weather.forecast"))));
    }
}
