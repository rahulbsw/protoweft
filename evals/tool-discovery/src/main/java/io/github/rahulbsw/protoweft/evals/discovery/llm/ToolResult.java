package io.github.rahulbsw.protoweft.evals.discovery.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to one tool call, sent back to the model on the next turn.
 *
 * @param callId  the {@link ToolCall#id()} being answered
 * @param content result text (for a search tool: the matches). When {@code raw} is set, this is
 *                what the raw answer puts into the model's context (for example the definitions a
 *                {@code tool_reference} expands to); it then feeds token estimates only
 * @param error   whether the call failed
 * @param raw     a provider-specific answer sent verbatim instead of {@code content}, or
 *                {@code null}: an Anthropic {@code tool_result} content block (for example with
 *                {@code tool_reference} blocks) or an OpenAI input item (for example
 *                {@code tool_search_output})
 */
public record ToolResult(String callId, String content, boolean error, Map<String, Object> raw) {

    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        content = content == null ? "" : content;
        raw = raw == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    public ToolResult(String callId, String content, boolean error) {
        this(callId, content, error, null);
    }
}
