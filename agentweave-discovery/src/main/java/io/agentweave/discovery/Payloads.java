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
        return searchSchema("Maximum results (default " + defaultLimit + ")");
    }

    /** The meta-tool's schema: its result count never drops below {@code minimum}. */
    static Map<String, Object> searchSchemaAtLeast(int minimum, int maximum) {
        return searchSchema("How many results to return (at least " + minimum + ", at most " + maximum + ")");
    }

    private static Map<String, Object> searchSchema(String limitDescription) {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "What you need to do, in plain words"),
                        "limit", Map.of("type", "integer", "description", limitDescription)),
                "required", List.of("query"));
    }
}
