package io.github.rahulbsw.protoweft.evals.discovery.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real SDK clients against a canned localhost server: request bodies, response
 * parsing, usage normalization and conversation replay. Offline: no keys, no internet.
 */
class SdkClientsTest {

    private static final ToolSpec GEOCODE = new ToolSpec("geocode", "Find coordinates", Map.of("type", "object",
            "properties", Map.of("q", Map.of("type", "string")), "required", List.of("q")));

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<Map<String, Object>> bodies = new ArrayList<>();
    private String reply;
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            bodies.add(Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void anthropicMessagesRoundTrip() {
        reply = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"text","text":"Calling."},
                            {"type":"tool_use","id":"toolu_1","name":"geocode","input":{"q":"Paris"}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":30,"cache_creation_input_tokens":5}}""";
        AnthropicModelClient client = new AnthropicModelClient(AnthropicOkHttpClient.builder().baseUrl(base())
                .apiKey("test-key").maxRetries(0).build(),
                new AnthropicModelClient.Options("claude-test", null, "low", null, true));
        ModelResponse r = client.send(new ModelRequest("sys", List.of(new Turn.User("Where is Paris?")),
                List.of(GEOCODE), 256));
        assertThat(paths.get(0)).isEqualTo("/v1/messages");
        Map<String, Object> body = bodies.get(0);
        assertThat(body).containsEntry("model", "claude-test").containsEntry("max_tokens", 256)
                .containsEntry("system", "sys").doesNotContainKey("temperature");
        Map<String, Object> tool = Json.asMap(Json.asList(body.get("tools")).get(0));
        assertThat(tool).containsEntry("name", "geocode").containsKey("cache_control");
        assertThat(Json.asMap(tool.get("input_schema"))).containsEntry("required", List.of("q"));
        assertThat(Json.asMap(body.get("output_config"))).containsEntry("effort", "low");

        assertThat(r.turn().text()).isEqualTo("Calling.");
        assertThat(r.turn().toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("toolu_1");
            assertThat(c.arguments()).containsEntry("q", "Paris");
        });
        assertThat(r.usage()).isEqualTo(new Usage(135, 20, 30, 5));
        assertThat(r.stopReason()).isEqualTo("tool_use");

        // Replay: assistant message verbatim, then the tool result.
        client.send(new ModelRequest(null, List.of(new Turn.User("Where is Paris?"), r.turn(),
                new Turn.ToolResults(List.of(new ToolResult("toolu_1", "48.85,2.35", false)))), List.of(GEOCODE), 64));
        List<Object> messages = Json.asList(bodies.get(1).get("messages"));
        assertThat(messages).hasSize(3);
        assertThat(Json.asMap(messages.get(1))).containsEntry("role", "assistant");
        Map<String, Object> result = Json.asMap(Json.asList(Json.asMap(messages.get(2)).get("content")).get(0));
        assertThat(result).containsEntry("type", "tool_result").containsEntry("tool_use_id", "toolu_1");
    }

    @Test
    void anthropicTemperatureIsSentRawOnlyWhenSetAndErrorsCarryStatus() {
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"temperature not supported\"}}";
        status = 400;
        AnthropicModelClient client = new AnthropicModelClient(AnthropicOkHttpClient.builder().baseUrl(base())
                .apiKey("test-key").maxRetries(0).build(),
                new AnthropicModelClient.Options("claude-test", 0.0, null, "disabled", false));
        assertThatThrownBy(() -> client.send(new ModelRequest(null, List.of(new Turn.User("hi")), List.of(), 16)))
                .isInstanceOfSatisfying(ModelCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.isClientError()).isTrue();
                    assertThat(e.getMessage()).doesNotContain("test-key");
                });
        assertThat(bodies.get(0)).containsEntry("temperature", 0.0);
        assertThat(Json.asMap(bodies.get(0).get("thinking"))).containsEntry("type", "disabled");
    }

    @Test
    void anthropicCountTokens() {
        reply = "{\"input_tokens\": 321}";
        AnthropicTokenCounter counter = new AnthropicTokenCounter(AnthropicOkHttpClient.builder().baseUrl(base())
                .apiKey("test-key").maxRetries(0).build(), "claude-test");
        assertThat(counter.count(new ModelRequest(null, List.of(new Turn.User("hi")), List.of(GEOCODE), 1)))
                .isEqualTo(321);
        assertThat(paths.get(0)).isEqualTo("/v1/messages/count_tokens");
        assertThat(bodies.get(0)).containsKey("tools").doesNotContainKey("max_tokens");
    }

    @Test
    void openAiResponsesRoundTrip() {
        reply = """
                {"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"gpt-test",
                 "output":[{"type":"reasoning","id":"rs_1","summary":[]},
                           {"type":"function_call","id":"fc_1","call_id":"call_1","name":"geocode",
                            "arguments":"{\\"q\\":\\"Paris\\"}","status":"completed"}],
                 "usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":40},"output_tokens":20,
                          "output_tokens_details":{"reasoning_tokens":5},"total_tokens":120},
                 "parallel_tool_calls":true,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,
                 "instructions":null,"metadata":{},"temperature":null,"top_p":null}""";
        OpenAiModelClient client = new OpenAiModelClient(OpenAIOkHttpClient.builder().baseUrl(base() + "/v1")
                .apiKey("test-key").maxRetries(0).build(), new OpenAiModelClient.Options("gpt-test", null, "low"));
        ModelResponse r = client.send(new ModelRequest("sys", List.of(new Turn.User("Where is Paris?")),
                List.of(GEOCODE), 256));
        assertThat(paths.get(0)).isEqualTo("/v1/responses");
        Map<String, Object> body = bodies.get(0);
        assertThat(body).containsEntry("model", "gpt-test").containsEntry("instructions", "sys")
                .containsEntry("max_output_tokens", 256).doesNotContainKey("temperature");
        Map<String, Object> tool = Json.asMap(Json.asList(body.get("tools")).get(0));
        assertThat(tool).containsEntry("type", "function").containsEntry("name", "geocode").containsEntry("strict", false);
        assertThat(Json.asMap(body.get("reasoning"))).containsEntry("effort", "low");

        assertThat(r.turn().toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("call_1");
            assertThat(c.arguments()).containsEntry("q", "Paris");
        });
        assertThat(r.usage()).isEqualTo(new Usage(100, 20, 40, 0));
        assertThat(r.stopReason()).isEqualTo("completed");

        client.send(new ModelRequest(null, List.of(new Turn.User("Where is Paris?"), r.turn(),
                new Turn.ToolResults(List.of(new ToolResult("call_1", "48.85,2.35", false)))), List.of(GEOCODE), 64));
        List<Object> input = Json.asList(bodies.get(1).get("input"));
        assertThat(input).extracting(i -> Json.asMap(i).get("type"))
                .containsExactly("message", "reasoning", "function_call", "function_call_output");
    }
}
