package io.agentweave.mcp;

import java.util.Objects;

/**
 * One argument of an MCP prompt. Prompt arguments are always strings.
 *
 * @param name        the argument name
 * @param title       a display name, or {@code null}
 * @param description what to supply, or {@code null}
 * @param required    whether {@code prompts/get} must supply it
 */
public record McpPromptArgument(String name, String title, String description, boolean required) {

    public McpPromptArgument {
        Objects.requireNonNull(name, "name");
    }

    public static McpPromptArgument required(String name, String description) {
        return new McpPromptArgument(name, null, description, true);
    }

    public static McpPromptArgument optional(String name, String description) {
        return new McpPromptArgument(name, null, description, false);
    }
}
