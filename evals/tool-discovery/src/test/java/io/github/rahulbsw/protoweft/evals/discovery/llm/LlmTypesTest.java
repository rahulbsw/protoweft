package io.github.rahulbsw.protoweft.evals.discovery.llm;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmTypesTest {

    @Test
    void jtokkitEstimatesOffline() {
        TokenEstimator e = TokenEstimator.o200k();
        assertThat(e.text("hello world")).isEqualTo(2);
        assertThat(e.text("")).isZero();
        ToolSpec t = new ToolSpec("get_weather", "Weather for a city",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))));
        long tools = e.tools(List.of(t));
        assertThat(tools).isGreaterThan(10).isLessThan(60);
        ModelRequest r = new ModelRequest("sys", List.of(new Turn.User("hello world")), List.of(t), 10);
        assertThat(e.request(r)).isEqualTo(tools + 2 + e.text("sys"));
    }

    @Test
    void usageNormalization() {
        Usage u = new Usage(100, 5, 60, 30).plus(new Usage(10, 1, 0, 0));
        assertThat(u).isEqualTo(new Usage(110, 6, 60, 30));
        assertThat(u.uncachedInputTokens()).isEqualTo(20);
    }

    @Test
    void openAiArgumentParsingKeepsRawTextOnErrors() {
        ToolCall ok = OpenAiModelClient.parseCall("c1", "f", "{\"a\": 1}");
        assertThat(ok.arguments()).containsEntry("a", 1);
        assertThat(ok.argumentError()).isNull();
        ToolCall bad = OpenAiModelClient.parseCall("c2", "f", "{not json");
        assertThat(bad.arguments()).isEmpty();
        assertThat(bad.rawArguments()).isEqualTo("{not json");
        assertThat(bad.argumentError()).startsWith("invalid JSON");
        assertThat(OpenAiModelClient.parseCall("c3", "f", "[1]").argumentError()).contains("not a JSON object");
        assertThat(OpenAiModelClient.parseCall("c4", "f", "").arguments()).isEmpty();
    }

    @Test
    void sdkToolDefinitionsPassTheSchemaThrough() {
        ToolSpec t = new ToolSpec("lookup", "Look up", Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string")), "required", List.of("id"),
                "additionalProperties", false));
        com.anthropic.models.messages.Tool a = AnthropicModelClient.toTool(t);
        assertThat(a.name()).isEqualTo("lookup");
        assertThat(a.inputSchema().required()).contains(List.of("id"));
        assertThat(a.inputSchema()._additionalProperties()).containsKey("additionalProperties");
        com.openai.models.responses.FunctionTool o = OpenAiModelClient.toFunctionTool(t);
        assertThat(o.name()).isEqualTo("lookup");
        assertThat(o.strict()).contains(false);
        assertThat(o.parameters().orElseThrow()._allProperties()).containsKeys("type", "properties", "required");
    }

    @Test
    void conversationsConvertForBothProvidersWithoutNetwork() {
        List<Turn> conv = List.of(new Turn.User("q"),
                new Turn.Assistant("", List.of(ToolCall.of("call_1", "search_tools", Map.of("query", "maps"))), null),
                new Turn.ToolResults(List.of(new ToolResult("call_1", "found: geocode", false))));
        assertThat(AnthropicModelClient.messages(conv)).hasSize(3);
        assertThat(OpenAiModelClient.inputItems(conv)).hasSize(3);
    }

    @Test
    void modelSpecsAndKeys() {
        assertThat(ModelClients.parse("openai:gpt-6.1-sol")).containsExactly("openai", "gpt-6.1-sol");
        assertThatThrownBy(() -> ModelClients.parse("nocolon")).isInstanceOf(IllegalArgumentException.class);
        ModelClient dry = ModelClients.estimating("anthropic:x", TokenEstimator.o200k());
        assertThat(dry.key()).isEqualTo("anthropic:x");
        ModelResponse r = dry.send(new ModelRequest(null, List.of(new Turn.User("hello world")), List.of(), 5));
        assertThat(r.usage().inputTokens()).isEqualTo(2);
        assertThat(r.turn().toolCalls()).isEmpty();
    }

    @Test
    void tokenCountingClientDrains() {
        ScriptedModelClient fake = new ScriptedModelClient("p", "m", req -> ScriptedModelClient.text("", Usage.ZERO));
        TokenCountingClient c = new TokenCountingClient(fake, req -> 7);
        ModelRequest req = new ModelRequest(null, List.of(new Turn.User("x")), List.of(), 5);
        c.send(req);
        c.send(req);
        assertThat(c.drain()).isEqualTo(14);
        assertThat(c.drain()).isZero();
    }
}
