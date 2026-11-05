package io.agentweave.evals.discovery.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.Json;

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

    /** System prompt + every turn's text, arguments and tool results + tool definitions. */
    public long request(ModelRequest request) {
        long total = text(request.system()) + tools(request.tools());
        for (Turn turn : request.conversation()) {
            if (turn instanceof Turn.User u) {
                total += text(u.text());
            } else if (turn instanceof Turn.Assistant a) {
                total += text(a.text());
                for (ToolCall c : a.toolCalls()) {
                    total += text(c.name()) + text(Json.write(c.arguments()));
                }
            } else if (turn instanceof Turn.ToolResults r) {
                for (ToolResult res : r.results()) {
                    total += text(res.content());
                }
            }
        }
        return total;
    }
}
