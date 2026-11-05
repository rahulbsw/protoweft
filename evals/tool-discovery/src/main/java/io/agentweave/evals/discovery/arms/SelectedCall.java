package io.agentweave.evals.discovery.arms;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A real (non-discovery) tool call, mapped back from the provider-facing name.
 *
 * @param toolId        catalog tool id, or {@code null} when the name matches no catalog tool
 *                      (a hallucinated tool, counted as a wrong call)
 * @param name          the name the model used
 * @param arguments     parsed arguments
 * @param argumentError argument parse error, or {@code null}
 */
public record SelectedCall(String toolId, String name, Map<String, Object> arguments, String argumentError) {

    public SelectedCall {
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
