package io.github.rahulbsw.protoweft.evals.discovery.arms;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.llm.AnthropicModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.OpenAiModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static io.github.rahulbsw.protoweft.evals.discovery.arms.DiscoveryArmsTest.CATALOG;
import static io.github.rahulbsw.protoweft.evals.discovery.arms.DiscoveryArmsTest.TASK;
import static io.github.rahulbsw.protoweft.evals.discovery.arms.DiscoveryArmsTest.arm;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The native arms through the real SDK clients against a canned localhost server: the exact
 * request JSON (deferred tools, the client tool_search tool) and the answers sent back
 * (tool_reference blocks, tool_search_output items). Offline: no keys, no internet.
 */
class NativeArmsTest {

    private HttpServer server;
    private final List<Map<String, Object>> bodies = new ArrayList<>();
    private final Deque<String> replies = new ArrayDeque<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = replies.removeFirst().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
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

    private static String anthropicToolUse(String id, String name, String input) {
        return """
                {"id":"msg_%s","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"tool_use","id":"%s","name":"%s","input":%s}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}"""
                .formatted(id, id, name, input);
    }

    @Test
    void nativeAnthropicDefersTheCatalogAndAnswersWithToolReferences(@TempDir Path cache) {
        replies.add(anthropicToolUse("toolu_1", DiscoveryTools.SEARCH, "{\"query\":\"geocode a place\"}"));
        replies.add(anthropicToolUse("toolu_2", "test__geocode", "{\"q\":\"Paris\"}"));
        AnthropicModelClient client = new AnthropicModelClient(AnthropicOkHttpClient.builder().baseUrl(base())
                .apiKey("test-key").maxRetries(0).build(),
                new AnthropicModelClient.Options("claude-test", null, null, null, true));
        Arm arm = arm("native-anthropic", cache);
        ArmResult r = arm.run(TASK, CATALOG, client);

        assertThat(arm.name()).isEqualTo("native-anthropic(catalog-bm25,fallback=progressive)");
        assertThat(bodies).hasSize(2);
        List<Map<String, Object>> tools = Json.asList(bodies.get(0).get("tools")).stream().map(Json::asMap).toList();
        assertThat(tools).hasSize(1 + CATALOG.size());
        assertThat(tools.get(0)).containsEntry("name", DiscoveryTools.SEARCH).doesNotContainKey("defer_loading")
                .containsKey("cache_control"); // the cache breakpoint goes on the non-deferred tool
        assertThat(tools.subList(1, tools.size())).allSatisfy(t -> assertThat(t)
                .containsEntry("defer_loading", true).containsKeys("name", "input_schema").doesNotContainKey("cache_control"));
        assertThat(tools).extracting(t -> t.get("name")).contains("test__geocode");
        assertThat(bodies.get(1).get("tools")).isEqualTo(bodies.get(0).get("tools")); // same tools every turn

        List<Object> messages = Json.asList(bodies.get(1).get("messages"));
        assertThat(messages).hasSize(3);
        Map<String, Object> result = Json.asMap(Json.asList(Json.asMap(messages.get(2)).get("content")).get(0));
        assertThat(result).containsEntry("type", "tool_result").containsEntry("tool_use_id", "toolu_1");
        List<Map<String, Object>> refs = Json.asList(result.get("content")).stream().map(Json::asMap).toList();
        assertThat(refs).isNotEmpty().allSatisfy(ref -> assertThat(ref).containsEntry("type", "tool_reference"));
        assertThat(refs.get(0)).containsEntry("tool_name", "test__geocode");

        assertThat(r.error()).isNull();
        assertThat(r.discoveryCalls()).isEqualTo(1);
        assertThat(r.modelCalls()).isEqualTo(2);
        assertThat(r.usage()).isEqualTo(new Usage(200, 40, 0, 0));
        assertThat(r.calls()).singleElement().satisfies(c -> {
            assertThat(c.toolId()).isEqualTo("g:geo");
            assertThat(c.arguments()).containsEntry("q", "Paris");
        });
        assertThat(r.toolsOffered()).isEqualTo(1 + CATALOG.size());
        assertThat((List<?>) r.extras().get("loaded")).first().isEqualTo("g:geo");
    }

    private static String openAiReply(String outputItem) {
        return """
                {"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"gpt-test",
                 "output":[%s],
                 "usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":40},"output_tokens":20,
                          "output_tokens_details":{"reasoning_tokens":5},"total_tokens":120},
                 "parallel_tool_calls":true,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,
                 "instructions":null,"metadata":{},"temperature":null,"top_p":null}""".formatted(outputItem);
    }

    @Test
    void nativeOpenAiAnswersClientToolSearchWithToolSearchOutput(@TempDir Path cache) {
        replies.add(openAiReply("""
                {"type":"tool_search_call","id":"tsc_1","call_id":"call_s1","execution":"client",
                 "status":"completed","arguments":{"query":"geocode a place"}}"""));
        replies.add(openAiReply("""
                {"type":"function_call","id":"fc_1","call_id":"call_f1","name":"test__geocode",
                 "arguments":"{\\"q\\":\\"Paris\\"}","status":"completed"}"""));
        OpenAiModelClient client = new OpenAiModelClient(OpenAIOkHttpClient.builder().baseUrl(base() + "/v1")
                .apiKey("test-key").maxRetries(0).build(), new OpenAiModelClient.Options("gpt-test", null, null));
        Arm arm = arm("native-openai", cache);
        ArmResult r = arm.run(TASK, CATALOG, client);

        assertThat(arm.name()).isEqualTo("native-openai(catalog-bm25,fallback=progressive)");
        List<Map<String, Object>> tools = Json.asList(bodies.get(0).get("tools")).stream().map(Json::asMap).toList();
        assertThat(tools).singleElement().satisfies(t -> assertThat(t).containsEntry("type", "tool_search")
                .containsEntry("execution", "client").containsKey("parameters"));

        List<Map<String, Object>> input = Json.asList(bodies.get(1).get("input")).stream().map(Json::asMap).toList();
        assertThat(input).extracting(i -> i.get("type")).containsExactly("message", "tool_search_call", "tool_search_output");
        assertThat(input.get(1)).containsEntry("call_id", "call_s1").containsEntry("execution", "client");
        Map<String, Object> output = input.get(2);
        assertThat(output).containsEntry("call_id", "call_s1").containsEntry("execution", "client")
                .containsEntry("status", "completed");
        List<Map<String, Object>> loaded = Json.asList(output.get("tools")).stream().map(Json::asMap).toList();
        assertThat(loaded).isNotEmpty().allSatisfy(f -> assertThat(f).containsEntry("type", "function")
                .containsKeys("name", "parameters"));
        assertThat(loaded.get(0)).containsEntry("name", "test__geocode");

        assertThat(r.error()).isNull();
        assertThat(r.discoveryCalls()).isEqualTo(1);
        assertThat(r.usage()).isEqualTo(new Usage(200, 40, 80, 0));
        assertThat(r.calls()).singleElement().satisfies(c -> {
            assertThat(c.toolId()).isEqualTo("g:geo");
            assertThat(c.arguments()).containsEntry("q", "Paris");
        });
    }

    @Test
    void nativeOpenAiCanAlsoDeclareTheCatalogAsDeferredNamespaces(@TempDir Path cache) {
        replies.add(openAiReply("""
                {"type":"message","id":"m1","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":"no tool","annotations":[]}]}"""));
        OpenAiModelClient client = new OpenAiModelClient(OpenAIOkHttpClient.builder().baseUrl(base() + "/v1")
                .apiKey("test-key").maxRetries(0).build(), new OpenAiModelClient.Options("gpt-test", null, null));
        Arm arm = arm("native-openai", cache, "deferred", "namespaces");
        ArmResult r = arm.run(TASK, CATALOG, client);
        assertThat(arm.name()).endsWith(",deferred=namespaces)");
        List<Map<String, Object>> tools = Json.asList(bodies.get(0).get("tools")).stream().map(Json::asMap).toList();
        assertThat(tools.get(0)).containsEntry("type", "tool_search");
        assertThat(tools.subList(1, tools.size())).isNotEmpty().allSatisfy(ns -> {
            assertThat(ns).containsEntry("type", "namespace").doesNotContainKey("defer_loading");
            assertThat(Json.asList(ns.get("tools"))).allSatisfy(f -> assertThat(Json.asMap(f))
                    .containsEntry("defer_loading", true));
        });
        assertThat(r.calls()).isEmpty();
        assertThat(r.finalText()).isEqualTo("no tool");
    }
}
