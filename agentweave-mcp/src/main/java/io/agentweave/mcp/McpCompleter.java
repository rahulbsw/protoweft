package io.agentweave.mcp;

import java.util.List;
import java.util.Locale;

/**
 * Suggests values for one prompt argument or template variable. The server returns the first
 * 100 suggestions and reports the total.
 */
@FunctionalInterface
public interface McpCompleter {

    List<String> complete(McpCompletionRequest request) throws Exception;

    /** Suggests the {@code candidates} that start with the typed value, ignoring case. */
    static McpCompleter of(List<String> candidates) {
        List<String> all = List.copyOf(candidates);
        return request -> {
            String prefix = request.value().toLowerCase(Locale.ROOT);
            return all.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        };
    }
}
