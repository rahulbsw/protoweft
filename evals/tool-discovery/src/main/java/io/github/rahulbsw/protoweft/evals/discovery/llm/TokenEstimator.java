package io.github.rahulbsw.protoweft.evals.discovery.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline token estimates with jtokkit. {@code o200k_base} is OpenAI's tokenizer for current
 * models, so estimates for OpenAI are close; Claude uses a different tokenizer, so for Anthropic
 * these are only approximations (use {@link AnthropicTokenCounter} for exact counts). Provider
 * {@code usage} remains the source of truth for reported tokens; estimates feed the cost guard
 * (multiplied by a safety factor) and the "tool-definition payload" column.
 *
 * <p>Tools are serialized as {@code {"name","description","parameters"}} JSON — the payload the
 * model actually reads — without provider framing tokens, which are small and constant.
 */
public final class TokenEstimator {

    private final Encoding encoding;

    private TokenEstimator(Encoding encoding) {
        this.encoding = encoding;
    }

    public static TokenEstimator o200k() {
        return new TokenEstimator(Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE));
    }

    public long text(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokensOrdinary(text);
    }

    /** Tokens of the tool definitions as JSON. */
    public long tools(List<ToolSpec> tools) {
        long total = 0;
        for (ToolSpec t : tools) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", t.name());
            def.put("description", t.description());
            def.put("parameters", t.inputSchema());
            total += text(Json.write(def));
        }
        return total;
    }

    /**
     * Tokens of provider-specific raw tool definitions, conservatively: a non-deferred tool counts
     * as its full JSON; a {@code defer_loading} tool counts its name and description only (OpenAI
     * shows those up front; Anthropic shows nothing, so this over-estimates there); a namespace
     * counts its own name and description plus its members by the same rule.
     */
    public long rawTools(List<Map<String, Object>> tools) {
        long total = 0;
        for (Map<String, Object> t : tools) {
            total += rawTool(t);
        }
        return total;
    }

    private long rawTool(Map<String, Object> t) {
        if ("namespace".equals(t.get("type"))) {
            long total = text(String.valueOf(t.getOrDefault("name", ""))) + text(String.valueOf(t.getOrDefault("description", "")));
            for (Object inner : Json.asList(t.get("tools"))) {
                total += rawTool(Json.asMap(inner));
            }
            return total;
        }
        if (Boolean.TRUE.equals(t.get("defer_loading"))) {
            return text(String.valueOf(t.getOrDefault("name", ""))) + text(String.valueOf(t.getOrDefault("description", "")));
        }
        return text(Json.write(t));
    }

    /**
     * The segmented estimate for one request: system prompt, tool definitions (neutral and raw),
     * tool results, and conversation history (user turns plus assistant text and tool-call
     * name/arguments). A raw tool result counts its {@code content} (what it expands to in context),
     * or its JSON when the content is empty.
     */
    public TokenBreakdown breakdown(ModelRequest request) {
        long system = text(request.system());
        long definitions = tools(request.tools()) + rawTools(request.rawTools());
        long results = 0;
        long history = 0;
        for (Turn turn : request.conversation()) {
            if (turn instanceof Turn.User u) {
                history += text(u.text());
            } else if (turn instanceof Turn.Assistant a) {
                history += text(a.text());
                for (ToolCall c : a.toolCalls()) {
                    history += text(c.name()) + text(Json.write(c.arguments()));
                }
            } else if (turn instanceof Turn.ToolResults r) {
                for (ToolResult res : r.results()) {
                    results += !res.content().isEmpty() || res.raw() == null
                            ? text(res.content()) : text(Json.write(res.raw()));
                }
            }
        }
        return new TokenBreakdown(system, definitions, results, history);
    }

    /**
     * System prompt + every turn's text, arguments and tool results + tool definitions (neutral
     * and raw); the sum of {@link #breakdown(ModelRequest)}.
     */
    public long request(ModelRequest request) {
        return breakdown(request).total();
    }
}
