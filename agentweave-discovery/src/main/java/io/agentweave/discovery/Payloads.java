package io.agentweave.discovery;

import io.agentweave.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Shared pieces of the provider payload builders. */
final class Payloads {

    static final String SEARCH_DESCRIPTION = "Search the available tools by what you need to do. Call it before "
            + "using any tool you have not loaded yet; matching tools become available to call.";

    private Payloads() {
    }

    /** Full specs of every capability passing the filter's exact checks, by catalog name. */
    static List<ToolSpec> permitted(CapabilityCatalog catalog, DiscoveryQuery filter) {
        List<ToolSpec> out = new ArrayList<>();
        catalog.cards().stream()
                .filter(filter::matchesFilters)
                .filter(filter::permits)
                .filter(c -> filter.includeUnavailable() || catalog.unavailableReason(c.source()).isEmpty())
                .sorted(Comparator.comparing(CapabilityCard::name))
                .forEach(c -> catalog.schema(c.name()).ifPresent(out::add));
        return out;
    }

    static Map<String, Object> inputSchema(ToolSpec spec) {
        return spec.inputSchema().isEmpty() ? Map.of("type", "object", "properties", Map.of()) : spec.inputSchema();
    }

    static Map<String, Object> searchSchema() {
        return searchSchema(DiscoveryQuery.DEFAULT_LIMIT);
    }

    static Map<String, Object> searchSchema(int defaultLimit) {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "What you need to do, in plain words"),
                        "limit", Map.of("type", "integer", "description", "Maximum results (default " + defaultLimit + ")")),
                "required", List.of("query"));
    }
}
