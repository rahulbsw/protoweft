package io.agentweave.evals.discovery.llm;

import io.agentweave.core.tool.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * One provider-neutral model call.
 *
 * @param system          system prompt, or {@code null}
 * @param conversation    the turns so far; the last one is a {@link Turn.User} or {@link Turn.ToolResults}
 * @param tools           tool definitions to offer (provider-safe names, JSON Schema inputs)
 * @param maxOutputTokens output token cap (also the worst case the cost guard assumes)
 */
public record ModelRequest(String system, List<Turn> conversation, List<ToolSpec> tools, int maxOutputTokens) {

    public ModelRequest {
        conversation = List.copyOf(Objects.requireNonNull(conversation, "conversation"));
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
