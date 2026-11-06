package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * A {@code completion/complete} handed to an {@link McpCompleter}.
 *
 * @param argument the prompt argument or template variable being completed
 * @param value    what the user has typed so far
 * @param context  values already chosen for the other arguments or variables
 * @param caller   who is asking, as authenticated by the host ({@code anonymous} over stdio)
 */
public record McpCompletionRequest(String argument, String value, Map<String, String> context, CallerIdentity caller) {

    public McpCompletionRequest {
        Objects.requireNonNull(argument, "argument");
        value = value == null ? "" : value;
        context = context == null ? Map.of() : Map.copyOf(context);
        caller = caller == null ? CallerIdentity.ANONYMOUS : caller;
    }
}
