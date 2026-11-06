package io.github.rahulbsw.protoweft.mcp;

import java.util.List;
import java.util.Objects;

/**
 * A prompt template an MCP server offers, as listed by {@code prompts/list}.
 *
 * @param name        the prompt name
 * @param title       a display name, or {@code null}
 * @param description what the prompt is for, or {@code null}
 * @param arguments   the arguments it accepts
 */
public record McpPromptInfo(String name, String title, String description, List<McpPromptArgument> arguments) {

    public McpPromptInfo {
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public static McpPromptInfo of(String name, String description, McpPromptArgument... arguments) {
        return new McpPromptInfo(name, null, description, List.of(arguments));
    }
}
