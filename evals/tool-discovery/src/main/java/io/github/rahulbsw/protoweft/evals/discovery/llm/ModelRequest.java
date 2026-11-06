package io.github.rahulbsw.protoweft.evals.discovery.llm;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One provider-neutral model call.
 *
 * @param system          system prompt, or {@code null}
 * @param conversation    the turns so far; the last one is a {@link Turn.User} or {@link Turn.ToolResults}
 * @param tools           tool definitions to offer (provider-safe names, JSON Schema inputs)
 * @param maxOutputTokens output token cap (also the worst case the cost guard assumes)
 * @param rawTools        provider-specific tool definitions as raw JSON maps, sent verbatim after
 *                        {@code tools} (for example Anthropic {@code defer_loading} tools or an
 *                        OpenAI {@code tool_search} tool); empty for provider-neutral arms. Only
 *                        the client of the matching provider understands them.
 */
public record ModelRequest(String system, List<Turn> conversation, List<ToolSpec> tools, int maxOutputTokens,
                           List<Map<String, Object>> rawTools) {

    public ModelRequest {
        conversation = List.copyOf(Objects.requireNonNull(conversation, "conversation"));
        tools = tools == null ? List.of() : List.copyOf(tools);
        rawTools = rawTools == null ? List.of()
                : rawTools.stream().map(m -> Collections.unmodifiableMap(new LinkedHashMap<>(m))).toList();
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    public ModelRequest(String system, List<Turn> conversation, List<ToolSpec> tools, int maxOutputTokens) {
        this(system, conversation, tools, maxOutputTokens, List.of());
    }
}
