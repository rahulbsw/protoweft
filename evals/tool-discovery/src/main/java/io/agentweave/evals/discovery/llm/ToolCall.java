package io.agentweave.evals.discovery.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool call the model made.
 *
 * @param id            provider call id (Anthropic {@code tool_use.id}, OpenAI {@code call_id})
 * @param name          the provider-facing tool name the model used
 * @param arguments     parsed arguments; empty if {@code rawArguments} was not a JSON object
 * @param rawArguments  arguments exactly as the provider returned them (JSON text)
 * @param argumentError a parse error for {@code rawArguments}, or {@code null}
 */
public record ToolCall(String id, String name, Map<String, Object> arguments, String rawArguments,
                       String argumentError) {

    public ToolCall {
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static ToolCall of(String id, String name, Map<String, Object> arguments) {
        return new ToolCall(id, name, arguments, null, null);
    }
}
