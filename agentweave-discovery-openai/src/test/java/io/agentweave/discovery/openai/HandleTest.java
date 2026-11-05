package io.agentweave.discovery.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.Tool;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoverySession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.agentweave.discovery.openai.Fixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;

class HandleTest {

    private final Fixtures.FileTools files = new Fixtures.FileTools();
    private final CapabilityCatalog catalog = Fixtures.catalog(files);

    private static ResponseOutputItem item(String json) {
        return Fixtures.parse(json, ResponseOutputItem.class);
    }

    private static ResponseOutputItem functionCall(String name, String arguments) {
        return item(SdkJson.write(Map.of("type", "function_call", "id", "fc_1", "call_id", "call_9",
                "name", name, "arguments", arguments, "status", "completed")));
    }

    private static String output(Optional<ResponseInputItem> answer) {
        assertThat(answer).isPresent();
        ResponseInputItem.FunctionCallOutput out = answer.get().asFunctionCallOutput();
        assertThat(out.callId()).contains("call_9");
        return out.output().asString();
    }

    @Test
    void clientToolSearchCallIsAnsweredFromTheCatalogAndRecordedInTheSession() {
        DiscoverySession session = new DiscoverySession(catalog);
        OpenAiDiscovery discovery = OpenAiDiscovery.builder(session).build();

        Optional<ResponseInputItem> answer = discovery.handle(item("""
                {"type":"tool_search_call","id":"tsc_1","execution":"client","call_id":"call_abc",
                 "status":"completed","arguments":{"query":"weather forecast for a city","limit":1}}"""));

        JsonNode out = tree(answer.orElseThrow());
        assertThat(out.get("type").asText()).isEqualTo("tool_search_output");
        assertThat(out.get("execution").asText()).isEqualTo("client");
        assertThat(out.get("call_id").asText()).isEqualTo("call_abc");
        assertThat(out.get("status").asText()).isEqualTo("completed");
        assertThat(out.get("tools")).hasSize(1);
        assertThat(out.get("tools").get(0).get("name").asText()).isEqualTo("weather__forecast");
        assertThat(out.get("tools").get(0).get("parameters").get("properties").has("city")).isTrue();
        assertThat(session.loaded()).containsExactly("weather.forecast");
    }

    @Test
    void searchWithoutAQueryLoadsNothing() {
        JsonNode out = tree(OpenAiDiscovery.of(catalog).handle(item("""
                {"type":"tool_search_call","id":"tsc_1","execution":"client","call_id":"call_abc",
                 "status":"completed","arguments":{"goal":"weather"}}""")).orElseThrow());
        assertThat(out.get("tools")).isEmpty();
    }

    @Test
    void hostedSearchStepsAndMessagesNeedNoAnswer() {
        OpenAiDiscovery discovery = OpenAiDiscovery.of(catalog);
        assertThat(discovery.handle(item("""
                {"type":"tool_search_call","id":"tsc_1","execution":"server","call_id":null,
                 "status":"completed","arguments":{"paths":["files"]}}"""))).isEmpty();
        assertThat(discovery.handle(item("""
                {"type":"message","id":"msg_1","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":"hi","annotations":[]}]}"""))).isEmpty();
    }

    @Test
    void functionCallInvokesTheCapability() {
        DiscoverySession session = new DiscoverySession(catalog);
        OpenAiDiscovery discovery = OpenAiDiscovery.builder(session).build();
        discovery.tools();

        assertThat(output(discovery.handle(functionCall("weather__forecast", "{\"city\":\"Paris\"}"))))
                .isEqualTo("sunny in Paris");
        assertThat(session.loaded()).containsExactly("weather.forecast");
        assertThat(tree(discovery.handle(functionCall("weather__forecast", "{\"city\":\"Oslo\"}")).orElseThrow())
                .get("type").asText()).isEqualTo("function_call_output");
    }

    @Test
    void namesAreResolvedEvenIfAnotherInstanceBuiltTheRequest() {
        assertThat(output(OpenAiDiscovery.of(catalog).handle(functionCall("files__readFile", "{\"path\":\"a.txt\"}"))))
                .isEqualTo("contents of a.txt");
    }

    @Test
    void unknownToolGetsAnErrorOutput() {
        assertThat(output(OpenAiDiscovery.of(catalog).handle(functionCall("nope__missing", "{}"))))
                .isEqualTo("Error: unknown tool: nope__missing");
    }

    @Test
    void forbiddenToolLooksExactlyLikeAnUnknownOne() {
        OpenAiDiscovery anonymous = OpenAiDiscovery.of(catalog);
        // Even malformed arguments must not reveal that the capability exists.
        assertThat(output(anonymous.handle(functionCall("files__deleteFile", "{\"path\":\"a.txt\"}"))))
                .isEqualTo("Error: unknown tool: files__deleteFile");
        assertThat(output(anonymous.handle(functionCall("files__deleteFile", "[1]"))))
                .isEqualTo("Error: unknown tool: files__deleteFile");
        assertThat(files.deletes).hasValue(0);

        OpenAiDiscovery writer = OpenAiDiscovery.builder(catalog).caller(Fixtures.WRITER).build();
        assertThat(output(writer.handle(functionCall("files__deleteFile", "{\"path\":\"a.txt\"}"))))
                .isEqualTo("deleted a.txt");
        assertThat(files.deletes).hasValue(1);
    }

    @Test
    void failuresAreReportedAsErrorText() {
        OpenAiDiscovery discovery = OpenAiDiscovery.of(catalog);
        assertThat(output(discovery.handle(functionCall("weather__archive", "{\"city\":\"Paris\",\"date\":\"2020-01-01\"}"))))
                .isEqualTo("Error: archive backend timed out");
        assertThat(output(discovery.handle(functionCall("weather__forecast", "not json"))))
                .startsWith("Error: arguments must be a JSON object");

        catalog.markUnavailable("weather", "maintenance");
        assertThat(output(discovery.handle(functionCall("weather__forecast", "{\"city\":\"Paris\"}"))))
                .isEqualTo("Error: capability weather.forecast is unavailable: maintenance");
    }

    @Test
    void unknownCallsCanBeLeftToTheApplication() {
        OpenAiDiscovery discovery = OpenAiDiscovery.builder(catalog).answerUnknownCalls(false).build();
        assertThat(discovery.handle(functionCall("my_own_function", "{}"))).isEmpty();
        assertThat(discovery.handle(functionCall("weather__forecast", "{\"city\":\"Rome\"}"))).isPresent();
    }

    @Test
    void sessionToolsSendSelectedCapabilitiesAsNormalFunctions() {
        DiscoverySession session = new DiscoverySession(catalog);
        session.select(List.of("files.readFile"));
        OpenAiDiscovery discovery = OpenAiDiscovery.builder(session).sessionTools(true).build();

        List<Tool> tools = discovery.tools();
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).isSearch()).isTrue();
        assertThat(tools.get(1).asFunction().name()).isEqualTo("files__readFile");
        assertThat(tools.get(1).asFunction().deferLoading()).isEmpty();

        assertThat(OpenAiDiscovery.builder(session).build().tools()).hasSize(1);
    }

    @Test
    void clientResponseFixtureYieldsSearchOutputAndFunctionOutputInOrder() {
        Response response = Fixtures.parse(Fixtures.resource("client-search-response.json"), Response.class);
        assertThat(response.output()).hasSize(4);

        List<ResponseInputItem> next = OpenAiDiscovery.of(catalog).nextInput(response);

        assertThat(next).hasSize(2);
        JsonNode search = tree(next.get(0));
        assertThat(search.get("type").asText()).isEqualTo("tool_search_output");
        assertThat(search.get("call_id").asText()).isEqualTo("call_abc123");
        assertThat(search.get("tools").findValuesAsText("name")).first().isEqualTo("weather__forecast");
        JsonNode call = tree(next.get(1));
        assertThat(call).isEqualTo(tree("""
                {"type":"function_call_output","call_id":"call_xyz456","output":"sunny in Paris"}"""));
    }

    @Test
    void hostedResponseFixtureOnlyNeedsTheFunctionOutput() {
        Response response = Fixtures.parse(Fixtures.resource("hosted-search-response.json"), Response.class);
        OpenAiDiscovery hosted = OpenAiDiscovery.builder(catalog).mode(OpenAiDiscovery.Mode.HOSTED).namespaces(true)
                .build();

        List<ResponseInputItem> next = hosted.nextInput(response);

        assertThat(next).hasSize(1);
        assertThat(tree(next.get(0))).isEqualTo(tree("""
                {"type":"function_call_output","call_id":"call_def789","output":"contents of notes.txt"}"""));
    }
}
