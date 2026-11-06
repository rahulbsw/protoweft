package io.github.rahulbsw.protoweft.discovery.anthropic;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.SourcePolicy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test catalogs built from fixed tool sources. */
final class TestCatalogs {

    static final CallerIdentity ADMIN = new CallerIdentity("admin",
            Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("files:write")));

    private TestCatalogs() {
    }

    /** A source with fixed specs that answers each call with "&lt;tool&gt;: &lt;args&gt;". */
    static final class FixedSource implements ToolSource {
        final List<ToolSpec> specs;
        final AtomicInteger calls = new AtomicInteger();

        FixedSource(ToolSpec... specs) {
            this.specs = List.of(specs);
        }

        @Override
        public List<ToolSpec> listTools() {
            return specs;
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation) {
            calls.incrementAndGet();
            if (invocation.toolName().equals("failingSync")) {
                return ToolOutcome.failure("sync failed: upstream returned 503");
            }
            return ToolOutcome.text(invocation.toolName() + ": " + new java.util.TreeMap<>(invocation.arguments()));
        }
    }

    static FixedSource files() {
        return new FixedSource(
                spec("readFile", "Read the contents of a file from disk.", Map.of("path", Map.of("type", "string"))),
                spec("deleteFile", "Delete a file from disk.", Map.of("path", Map.of("type", "string"))),
                spec("listDirectory", "List the entries of a directory.", Map.of("path", Map.of("type", "string"))));
    }

    static FixedSource crm() {
        return new FixedSource(
                new ToolSpec("findCustomer", "Find a customer record by email or name.", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Email or name", "minLength", 2),
                                "status", Map.of("type", "string", "enum", List.of("active", "churned")),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 10),
                                "score", Map.of("type", "number", "default", 2.5),
                                "filters", Map.of("type", "object", "properties",
                                        Map.of("region", Map.of("type", "string")))),
                        "required", List.of("query"),
                        "additionalProperties", false,
                        "$schema", "https://json-schema.org/draft/2020-12/schema")),
                new ToolSpec("failingSync", "Synchronise the CRM with the billing system.", Map.of()));
    }

    static FixedSource weather() {
        return new FixedSource(spec("forecast", "Current weather forecast for a city.",
                Map.of("city", Map.of("type", "string", "description", "City name"))));
    }

    static ToolSpec spec(String name, String description, Map<String, Object> properties) {
        return new ToolSpec(name, description, Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet())));
    }

    /** files (deleteFile needs files:write), crm and weather. */
    static CapabilityCatalog catalog(FixedSource files, FixedSource crm, FixedSource weather) {
        return CapabilityCatalog.builder()
                .source("files", files, SourcePolicy.builder().toolScopes("deleteFile", "files:write").build())
                .source("crm", crm)
                .source("weather", weather)
                .build();
    }

    static CapabilityCatalog catalog() {
        return catalog(files(), crm(), weather());
    }

    /** Wire JSON of any SDK value or plain map, via the SDK's own mapper. */
    static JsonNode json(Object value) {
        try {
            var mapper = ObjectMappers.jsonMapper();
            return mapper.readTree(mapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T parse(String json, Class<T> type) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
