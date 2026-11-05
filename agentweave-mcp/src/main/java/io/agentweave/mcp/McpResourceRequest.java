package io.agentweave.mcp;

import io.agentweave.core.server.CallerIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * A {@code resources/read} handed to an {@link McpResourceHandler}.
 *
 * @param uri       the URI being read
 * @param variables for a template, the values of its variables, percent-decoded; else empty
 * @param caller    who is reading, as authenticated by the host ({@code anonymous} over stdio)
 */
public record McpResourceRequest(String uri, Map<String, String> variables, CallerIdentity caller) {

    public McpResourceRequest {
        Objects.requireNonNull(uri, "uri");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        caller = caller == null ? CallerIdentity.ANONYMOUS : caller;
    }

    /** The value of a template variable, or {@code null}. */
    public String variable(String name) {
        return variables.get(name);
    }
}
