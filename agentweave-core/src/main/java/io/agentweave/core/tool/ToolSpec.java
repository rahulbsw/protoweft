package io.agentweave.core.tool;

import java.util.Map;
import java.util.Objects;

/**
 * A protocol-neutral description of a callable tool.
 *
 * @param name        the tool's identifier, unique within one connector
 * @param description human-readable description, possibly empty
 * @param inputSchema JSON Schema for the arguments, as plain maps/lists/scalars
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
