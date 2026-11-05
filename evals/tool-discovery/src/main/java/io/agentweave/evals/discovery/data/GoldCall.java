package io.agentweave.evals.discovery.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A labeled tool call: the gold tool and its gold arguments.
 *
 * @param toolId    the {@link EvalTool#id()} of the gold tool
 * @param arguments gold arguments as plain maps/lists/scalars (may contain nulls)
 */
public record GoldCall(String toolId, Map<String, Object> arguments) {

    public GoldCall {
        Objects.requireNonNull(toolId, "toolId");
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
