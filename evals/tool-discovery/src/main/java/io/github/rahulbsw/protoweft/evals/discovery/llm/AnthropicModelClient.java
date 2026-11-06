package io.github.rahulbsw.protoweft.evals.discovery.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageContentParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigBetweenTools;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ModelClient} on the Anthropic Messages API ({@code com.anthropic:anthropic-java} 3.5.0).
 * The key comes from {@code ANTHROPIC_API_KEY} and is never logged. Tool schemas are passed
 * through verbatim as {@code input_schema}.
 */
public final class AnthropicModelClient implements ModelClient {

    /**
     * Request options; {@code null} fields are omitted so the model's defaults apply.
     *
     * @param model       model id, e.g. {@code claude-sonnet-5-5}
     * @param temperature sampling temperature, sent raw; current models reject it (400), so leave it
     *                    {@code null} unless the model is known to accept it
     * @param effort      {@code output_config.effort} (e.g. {@code low}, {@code medium}, {@code high})
     * @param thinking    {@code adaptive}, {@code between_tools} or {@code disabled}
     * @param cacheTools  mark the last tool definition with {@code cache_control: ephemeral}, so the
     *                    tool block is prompt-cached across requests (Anthropic caches only when
     *                    asked; OpenAI caches long prefixes automatically)
     */
    public record Options(String model, Double temperature, String effort, String thinking, boolean cacheTools) {
        public Options {
            Objects.requireNonNull(model, "model");
        }
    }

    private final AnthropicClient client;
    private final Options options;

    public AnthropicModelClient(AnthropicClient client, Options options) {
        this.client = Objects.requireNonNull(client, "client");
        this.options = Objects.requireNonNull(options, "options");
    }

    /** A client using {@code ANTHROPIC_API_KEY}; fails fast when the variable is unset. */
    public static AnthropicModelClient fromEnv(Options options) {
        return fromEnv(options, ModelClients.DEFAULT_MAX_RETRIES);
    }

    /** As {@link #fromEnv(Options)}, retrying each call up to {@code maxRetries} times. */
    public static AnthropicModelClient fromEnv(Options options, int maxRetries) {
        return new AnthropicModelClient(sdkFromEnv(maxRetries), options);
    }

    /** An SDK client with {@code ANTHROPIC_API_KEY}, at {@code ANTHROPIC_BASE_URL} when set. */
    static AnthropicClient sdkFromEnv(int maxRetries) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(requireKey()).maxRetries(maxRetries);
        String base = ModelClients.baseUrl(ModelClients.ANTHROPIC_BASE_URL);
        if (base != null) {
            b.baseUrl(base);
        }
        return b.build();
    }

    static String requireKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        }
        return key;
    }

    AnthropicClient sdk() {
        return client;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return options.model();
    }

    @Override
    public ModelResponse send(ModelRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(options.model())
                .maxTokens(request.maxOutputTokens());
        if (request.system() != null && !request.system().isBlank()) {
            b.system(request.system());
        }
        if (options.temperature() != null) {
            // SDK 3.5.0 no longer models `temperature` (current Claude models reject sampling
            // parameters); send it raw for older models that still accept it.
            b.putAdditionalBodyProperty("temperature", JsonValue.from(options.temperature()));
        }
        if (options.effort() != null) {
            b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(options.effort())).build());
        }
        if (options.thinking() != null) {
            switch (options.thinking()) {
                case "adaptive" -> b.thinking(ThinkingConfigAdaptive.builder().build());
                case "between_tools" -> b.thinking(ThinkingConfigBetweenTools.builder().build());
                case "disabled" -> b.thinking(ThinkingConfigDisabled.builder().build());
                default -> throw new IllegalArgumentException("unknown thinking mode: " + options.thinking());
            }
        }
        if (!request.rawTools().isEmpty()) {
            // Raw provider JSON (e.g. defer_loading tools): the whole tools array is sent verbatim.
            b.putAdditionalBodyProperty("tools", JsonValue.from(rawToolArray(request, options.cacheTools())));
        } else {
            for (int i = 0; i < request.tools().size(); i++) {
                Tool tool = toTool(request.tools().get(i));
                if (options.cacheTools() && i == request.tools().size() - 1) {
                    tool = tool.toBuilder().cacheControl(CacheControlEphemeral.builder().build()).build();
                }
                b.addTool(tool);
            }
        }
        messages(request.conversation()).forEach(b::addMessage);
        long t0 = System.nanoTime();
        Message message;
        try {
            message = client.messages().create(b.build());
        } catch (AnthropicServiceException e) {
            throw new ModelCallException("Anthropic API error " + e.statusCode() + ": " + e.getMessage(), e.statusCode(), e);
        } catch (RuntimeException e) {
            throw new ModelCallException("Anthropic call failed: " + e.getMessage(), -1, e);
        }
        long latency = (System.nanoTime() - t0) / 1_000_000;
        return new ModelResponse(toTurn(message), usage(message.usage()), latency,
                message.stopReason().map(Object::toString).orElse(null));
    }

    /** The Messages API tool definition for a spec; the schema is passed through unchanged. */
    static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        Map<String, Object> in = spec.inputSchema();
        schema.type(JsonValue.from(in.getOrDefault("type", "object")));
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        Json.asMap(in.get("properties")).forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        schema.properties(props.build());
        in.forEach((k, v) -> {
            if (k.equals("required")) {
                schema.required(Json.asList(v).stream().map(String::valueOf).toList());
            } else if (!k.equals("type") && !k.equals("properties")) {
                schema.putAdditionalProperty(k, JsonValue.from(v));
            }
        });
        Tool.Builder tool = Tool.builder().name(spec.name()).inputSchema(schema.build());
        if (!spec.description().isEmpty()) {
            tool.description(spec.description());
        }
        return tool.build();
    }

    /**
     * The {@code tools} array for a request with raw tools: neutral tools as Messages API JSON,
     * then the raw definitions verbatim. With {@code cacheTools}, the last non-deferred tool gets
     * {@code cache_control} (deferred tools may not carry it).
     */
    static List<Map<String, Object>> rawToolArray(ModelRequest request, boolean cacheTools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec spec : request.tools()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", spec.name());
            if (!spec.description().isEmpty()) {
                t.put("description", spec.description());
            }
            t.put("input_schema", spec.inputSchema().isEmpty() ? Map.of("type", "object") : spec.inputSchema());
            out.add(t);
        }
        request.rawTools().forEach(t -> out.add(new LinkedHashMap<>(t)));
        if (cacheTools) {
            for (int i = out.size() - 1; i >= 0; i--) {
                if (!Boolean.TRUE.equals(out.get(i).get("defer_loading"))) {
                    out.get(i).put("cache_control", Map.of("type", "ephemeral"));
                    break;
                }
            }
        }
        return out;
    }

    /** Neutral conversation to Messages API params, replaying recorded {@link Message}s verbatim. */
    static List<MessageParam> messages(List<Turn> conversation) {
        List<MessageParam> out = new ArrayList<>();
        for (Turn turn : conversation) {
            if (turn instanceof Turn.User u) {
                out.add(MessageParam.ofUser(u.text()));
            } else if (turn instanceof Turn.Assistant a) {
                if (a.providerState() instanceof Message m) {
                    out.add(m.toParam());
                } else {
                    List<ContentBlockParam> blocks = new ArrayList<>();
                    if (!a.text().isEmpty()) {
                        blocks.add(ContentBlockParam.ofText(a.text()));
                    }
                    for (ToolCall c : a.toolCalls()) {
                        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
                        c.arguments().forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
                        blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                .id(c.id()).name(c.name()).input(input.build()).build()));
                    }
                    out.add(MessageParam.ofAssistantOfContentBlockParams(blocks));
                }
            } else if (turn instanceof Turn.ToolResults r) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (ToolResult res : r.results()) {
                    if (res.raw() != null) {
                        // A raw tool_result block (e.g. tool_reference content), sent verbatim.
                        blocks.add(JsonValue.from(res.raw()).convert(ContentBlockParam.class));
                        continue;
                    }
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(res.callId()).content(res.content()).isError(res.error()).build()));
                }
                out.add(MessageParam.ofUser(MessageContentParam.ofContentBlockParams(blocks)));
            }
        }
        return out;
    }

    private static Turn.Assistant toTurn(Message message) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                text.append(block.asText().text());
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.asToolUse();
                calls.add(toolCall(tu));
            }
        }
        return new Turn.Assistant(text.toString(), calls, message);
    }

    @SuppressWarnings("unchecked")
    private static ToolCall toolCall(ToolUseBlock tu) {
        Map<String, Object> args;
        String error = null;
        try {
            Object converted = tu._input().convert(Object.class);
            args = converted instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
            if (!(converted instanceof Map)) {
                error = "tool input is not a JSON object";
            }
        } catch (RuntimeException e) {
            args = Map.of();
            error = e.getMessage();
        }
        return new ToolCall(tu.id(), tu.name(), args, Json.write(args), error);
    }

    static Usage usage(com.anthropic.models.messages.Usage u) {
        long read = u.cacheReadInputTokens().orElse(0L);
        long write = u.cacheCreationInputTokens().orElse(0L);
        return new Usage(u.inputTokens() + read + write, u.outputTokens(), read, write);
    }
}
