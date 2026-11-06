package io.github.rahulbsw.protoweft.evals.discovery.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseToolSearchCall;
import com.openai.models.responses.ResponseUsage;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ModelClient} on the OpenAI Responses API ({@code com.openai:openai-java} 4.100.0). The
 * key comes from {@code OPENAI_API_KEY} and is never logged. Tools are non-strict function tools
 * with the JSON Schema passed through verbatim (strict mode would require rewriting schemas, which
 * would change what is measured). Prior output items (reasoning, function calls, messages) are
 * replayed as input items on later turns.
 */
public final class OpenAiModelClient implements ModelClient {

    /**
     * {@link ToolCall#name()} given to a client-executed {@code tool_search_call} (which has no
     * name of its own); answer it with a raw {@code tool_search_output} {@link ToolResult}.
     */
    public static final String TOOL_SEARCH_CALL = "tool_search";

    /**
     * Request options; {@code null} fields are omitted so the model's defaults apply.
     *
     * @param model           model id, e.g. {@code gpt-6.1-sol}
     * @param temperature     sampling temperature; reasoning models may reject it
     * @param reasoningEffort {@code reasoning.effort} (e.g. {@code none}, {@code low}, {@code medium})
     */
    public record Options(String model, Double temperature, String reasoningEffort) {
        public Options {
            Objects.requireNonNull(model, "model");
        }
    }

    private final OpenAIClient client;
    private final Options options;

    public OpenAiModelClient(OpenAIClient client, Options options) {
        this.client = Objects.requireNonNull(client, "client");
        this.options = Objects.requireNonNull(options, "options");
    }

    /** A client using {@code OPENAI_API_KEY}; fails fast when the variable is unset. */
    public static OpenAiModelClient fromEnv(Options options) {
        return fromEnv(options, ModelClients.DEFAULT_MAX_RETRIES);
    }

    /** As {@link #fromEnv(Options)}, retrying each call up to {@code maxRetries} times. */
    public static OpenAiModelClient fromEnv(Options options, int maxRetries) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder().apiKey(key).maxRetries(maxRetries);
        String base = ModelClients.baseUrl(ModelClients.OPENAI_BASE_URL);
        if (base != null) {
            b.baseUrl(base);
        }
        return new OpenAiModelClient(b.build(), options);
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return options.model();
    }

    @Override
    public ModelResponse send(ModelRequest request) {
        ResponseCreateParams.Builder b = ResponseCreateParams.builder()
                .model(options.model())
                .maxOutputTokens((long) request.maxOutputTokens());
        if (request.system() != null && !request.system().isBlank()) {
            b.instructions(request.system());
        }
        if (options.temperature() != null) {
            b.temperature(options.temperature());
        }
        if (options.reasoningEffort() != null) {
            b.reasoning(Reasoning.builder().effort(ReasoningEffort.of(options.reasoningEffort())).build());
        }
        if (!request.rawTools().isEmpty()) {
            // Raw provider JSON (e.g. a client tool_search tool): the whole tools array is sent verbatim.
            b.putAdditionalBodyProperty("tools", JsonValue.from(rawToolArray(request)));
        } else {
            request.tools().forEach(t -> b.addTool(toFunctionTool(t)));
        }
        b.inputOfResponse(inputItems(request.conversation()));
        long t0 = System.nanoTime();
        Response response;
        try {
            response = client.responses().create(b.build());
        } catch (OpenAIServiceException e) {
            throw new ModelCallException("OpenAI API error " + e.statusCode() + ": " + e.getMessage(), e.statusCode(), e);
        } catch (RuntimeException e) {
            throw new ModelCallException("OpenAI call failed: " + e.getMessage(), -1, e);
        }
        long latency = (System.nanoTime() - t0) / 1_000_000;
        String status = response.status().map(Object::toString).orElse(null);
        return new ModelResponse(toTurn(response), response.usage().map(OpenAiModelClient::usage).orElse(Usage.ZERO),
                latency, status);
    }

    static FunctionTool toFunctionTool(ToolSpec spec) {
        FunctionTool.Parameters.Builder params = FunctionTool.Parameters.builder();
        spec.inputSchema().forEach((k, v) -> params.putAdditionalProperty(k, JsonValue.from(v)));
        if (!spec.inputSchema().containsKey("type")) {
            params.putAdditionalProperty("type", JsonValue.from("object"));
        }
        FunctionTool.Builder tool = FunctionTool.builder().name(spec.name()).parameters(params.build()).strict(false);
        if (!spec.description().isEmpty()) {
            tool.description(spec.description());
        }
        return tool.build();
    }

    /** Neutral tools as non-strict function JSON, then the raw definitions verbatim. */
    static List<Map<String, Object>> rawToolArray(ModelRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec spec : request.tools()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("type", "function");
            fn.put("name", spec.name());
            if (!spec.description().isEmpty()) {
                fn.put("description", spec.description());
            }
            Map<String, Object> params = new LinkedHashMap<>(spec.inputSchema());
            params.putIfAbsent("type", "object");
            fn.put("parameters", params);
            fn.put("strict", false);
            out.add(fn);
        }
        out.addAll(request.rawTools());
        return out;
    }

    /** Converts raw JSON (a map, or an SDK model object) to an input item with the SDK's own mapper. */
    static ResponseInputItem rawInputItem(Object raw) {
        return ObjectMappers.jsonMapper().convertValue(raw, ResponseInputItem.class);
    }

    static List<ResponseInputItem> inputItems(List<Turn> conversation) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (Turn turn : conversation) {
            if (turn instanceof Turn.User u) {
                items.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER).content(u.text()).build()));
            } else if (turn instanceof Turn.Assistant a) {
                if (a.providerState() instanceof List<?> outputs) {
                    for (Object o : outputs) {
                        if (o instanceof ResponseOutputItem item) {
                            replay(item, items);
                        }
                    }
                } else {
                    if (!a.text().isEmpty()) {
                        items.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.ASSISTANT).content(a.text()).build()));
                    }
                    for (ToolCall c : a.toolCalls()) {
                        items.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                                .callId(c.id()).name(c.name())
                                .arguments(c.rawArguments() != null ? c.rawArguments() : Json.write(c.arguments()))
                                .build()));
                    }
                }
            } else if (turn instanceof Turn.ToolResults r) {
                for (ToolResult res : r.results()) {
                    if (res.raw() != null) {
                        // A raw input item (e.g. tool_search_output), sent verbatim.
                        items.add(rawInputItem(res.raw()));
                        continue;
                    }
                    items.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                            .callId(res.callId()).output(res.content()).build()));
                }
            }
        }
        return items;
    }

    private static void replay(ResponseOutputItem item, List<ResponseInputItem> items) {
        if (item.isFunctionCall()) {
            items.add(ResponseInputItem.ofFunctionCall(item.asFunctionCall()));
        } else if (item.isReasoning()) {
            items.add(ResponseInputItem.ofReasoning(item.asReasoning()));
        } else if (item.isMessage()) {
            items.add(ResponseInputItem.ofResponseOutputMessage(item.asMessage()));
        } else if (item.isToolSearchCall() || item.isToolSearchOutput()) {
            // Output and input shapes of tool search items are the same JSON.
            items.add(rawInputItem(item));
        }
    }

    private static Turn.Assistant toTurn(Response response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall fc = item.asFunctionCall();
                calls.add(parseCall(fc.callId(), fc.name(), fc.arguments()));
            } else if (item.isToolSearchCall() && isClientExecuted(item)) {
                ResponseToolSearchCall ts = item.asToolSearchCall();
                Object args = ts._arguments().convert(Object.class);
                calls.add(args instanceof Map<?, ?>
                        ? new ToolCall(ts.callId().orElse(""), TOOL_SEARCH_CALL, Json.asMap(args), Json.write(args), null)
                        : new ToolCall(ts.callId().orElse(""), TOOL_SEARCH_CALL, Map.of(), String.valueOf(args),
                        "tool_search arguments are not a JSON object"));
            } else if (item.isMessage()) {
                for (ResponseOutputMessage.Content c : item.asMessage().content()) {
                    if (c.isOutputText()) {
                        text.append(c.asOutputText().text());
                    }
                }
            }
        }
        return new Turn.Assistant(text.toString(), calls, List.copyOf(response.output()));
    }

    private static boolean isClientExecuted(ResponseOutputItem item) {
        // Hosted searches say "server" and are answered by OpenAI; anything else is ours to answer.
        return !"server".equals(Json.asMap(ObjectMappers.jsonMapper().convertValue(item, Map.class)).get("execution"));
    }

    /** Parses function-call arguments; malformed JSON is kept raw with an error. */
    static ToolCall parseCall(String callId, String name, String rawArguments) {
        try {
            Object parsed = Json.parse(rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
            if (parsed instanceof Map<?, ?>) {
                return new ToolCall(callId, name, Json.asMap(parsed), rawArguments, null);
            }
            return new ToolCall(callId, name, Map.of(), rawArguments, "arguments are not a JSON object");
        } catch (RuntimeException e) {
            return new ToolCall(callId, name, Map.of(), rawArguments, "invalid JSON arguments: " + e.getMessage());
        }
    }

    static Usage usage(ResponseUsage u) {
        long cached = 0;
        try {
            cached = u.inputTokensDetails().cachedTokens();
        } catch (RuntimeException ignored) {
            // details missing: treat as no cache hit
        }
        return new Usage(u.inputTokens(), u.outputTokens(), cached, 0);
    }
}
