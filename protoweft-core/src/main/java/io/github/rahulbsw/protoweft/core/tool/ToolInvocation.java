package io.github.rahulbsw.protoweft.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A request to call one tool.
 *
 * @param toolName  the tool to call
 * @param arguments arguments as plain maps/lists/scalars; may contain {@code null} values
 */
public record ToolInvocation(String toolName, Map<String, Object> arguments) {

    public ToolInvocation {
        Objects.requireNonNull(toolName, "toolName");
        // LinkedHashMap rather than Map.copyOf: JSON arguments may legitimately hold nulls.
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static ToolInvocation of(String toolName, Map<String, Object> arguments) {
        return new ToolInvocation(toolName, arguments);
    }
}
