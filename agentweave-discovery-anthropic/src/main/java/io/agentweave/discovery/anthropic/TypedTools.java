package io.agentweave.discovery.anthropic;

import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolSearchToolBm25_20251119;
import com.anthropic.models.messages.ToolSearchToolRegex20251119;
import com.anthropic.models.messages.ToolUnion;

import java.util.List;
import java.util.Map;

/**
 * Converts the raw tool maps of {@link io.agentweave.discovery.AnthropicPayloads} into SDK
 * types, field by field, so that the serialized JSON stays identical to the raw map.
 */
final class TypedTools {

    private TypedTools() {
    }

    /** A raw {@code {name, description, input_schema, defer_loading?}} map as an SDK {@link Tool}. */
    static Tool tool(Map<String, Object> raw) {
        Tool.Builder tool = Tool.builder()
                .name((String) raw.get("name"))
                .inputSchema(inputSchema(asMap(raw.get("input_schema"))));
        if (raw.get("description") instanceof String description) {
            tool.description(description);
        }
        if (Boolean.TRUE.equals(raw.get("defer_loading"))) {
            tool.deferLoading(true);
        }
        return tool.build();
    }

    /**
     * A JSON Schema map as {@link Tool.InputSchema}. {@code type}, {@code properties} and
     * {@code required} use the typed fields; every other keyword is passed through as an
     * additional property. A schema without {@code type} stays without it, as in the raw map.
     */
    @SuppressWarnings("unchecked") // JsonValue is a JsonField<Nothing>, accepted for off-type values
    static Tool.InputSchema inputSchema(Map<String, Object> schema) {
        Tool.InputSchema.Builder b = Tool.InputSchema.builder();
        if (!schema.containsKey("type")) {
            b.type(JsonMissing.of());
        }
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            Object value = e.getValue();
            switch (e.getKey()) {
                case "type" -> b.type(JsonValue.from(value));
                case "properties" -> {
                    if (value instanceof Map<?, ?> properties) {
                        Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
                        properties.forEach((k, v) -> p.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
                        b.properties(p.build());
                    } else {
                        b.properties(JsonValue.from(value));
                    }
                }
                case "required" -> {
                    if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
                        b.required(list.stream().map(String.class::cast).toList());
                    } else {
                        b.required(JsonValue.from(value));
                    }
                }
                default -> b.putAdditionalProperty(e.getKey(), JsonValue.from(value));
            }
        }
        return b.build();
    }

    static ToolUnion hostedSearchTool(AnthropicDiscovery.Mode mode) {
        return switch (mode) {
            case HOSTED_REGEX -> ToolUnion.ofSearchToolRegex20251119(ToolSearchToolRegex20251119.builder()
                    .type(ToolSearchToolRegex20251119.Type.TOOL_SEARCH_TOOL_REGEX_20251119)
                    .build());
            case HOSTED_BM25 -> ToolUnion.ofSearchToolBm25_20251119(ToolSearchToolBm25_20251119.builder()
                    .type(ToolSearchToolBm25_20251119.Type.TOOL_SEARCH_TOOL_BM25_20251119)
                    .build());
            case CATALOG_SEARCH -> throw new IllegalArgumentException("CATALOG_SEARCH has no hosted search tool");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
