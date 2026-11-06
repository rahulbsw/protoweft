package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * A {@code prompts/get} handed to an {@link McpPromptHandler}. Required arguments are checked
 * before the handler runs.
 *
 * @param name      the prompt name
 * @param arguments the supplied arguments
 * @param caller    who is asking, as authenticated by the host ({@code anonymous} over stdio)
 */
public record McpPromptRequest(String name, Map<String, String> arguments, CallerIdentity caller) {

    public McpPromptRequest {
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        caller = caller == null ? CallerIdentity.ANONYMOUS : caller;
    }

    /** The value of an argument, or {@code null}. */
    public String argument(String name) {
        return arguments.get(name);
    }
}
