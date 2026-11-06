package io.github.rahulbsw.protoweft.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts tool input schemas between Protoweft's plain JSON-Schema maps and LangChain4j's
 * {@link JsonObjectSchema}, through LangChain4j's public {@link ToolSpecification#fromJson(String)}
 * / {@link ToolSpecification#toJson()} (see SPEC.md, "Schema conversion").
 *
 * <p>Nested keywords LangChain4j has no typed model for ({@code format}, {@code minimum},
 * {@code oneOf}, …) are kept losslessly as {@code JsonRawSchema}. Only the root must be a plain
 * object schema, so root-level keywords {@link JsonObjectSchema} cannot hold are dropped.
 */
public final class JsonSchemas {

    /** Root keywords {@link JsonObjectSchema} can represent (mirrors LangChain4j's JSON codec). */
    private static final Set<String> ROOT_KEYS =
            Set.of("type", "description", "properties", "required", "additionalProperties", "$defs");

    private JsonSchemas() {
    }

    /**
     * Converts an Protoweft input schema to LangChain4j's model.
     *
     * <p>Root normalization: a missing {@code type} means {@code object}; draft-07
     * {@code definitions} become {@code $defs} (and {@code #/definitions/} references are
     * rewritten); a schema-valued root {@code additionalProperties} becomes {@code true} (as
     * langchain4j-mcp does); other root keywords ({@code $schema}, {@code title}, {@code allOf}, …)
     * are dropped.
     *
     * @throws IllegalArgumentException if the root is not an object schema or the schema is malformed
     */
    public static JsonObjectSchema toJsonObjectSchema(Map<String, Object> inputSchema) {
        Map<String, Object> root = normalizeRoot(inputSchema == null ? Map.of() : inputSchema);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "schema");
        spec.put("parameters", root);
        return ToolSpecification.fromJson(Json.write(spec)).parameters();
    }

    /** Converts LangChain4j's model to a plain JSON-Schema map ({@code {"type":"object"}} for {@code null}). */
    public static Map<String, Object> toMap(JsonObjectSchema schema) {
        if (schema == null) {
            return Map.of("type", "object");
        }
        String json = ToolSpecification.builder().name("schema").parameters(schema).build().toJson();
        try {
            Object parameters = Json.parseObject(json).get("parameters");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parameters;
            return map;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LangChain4j produced unreadable schema JSON", e);
        }
    }

    private static Map<String, Object> normalizeRoot(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type != null && !"object".equals(type)) {
            throw new IllegalArgumentException("tool input schema must have type \"object\", was: " + type);
        }
        Map<String, Object> root = new LinkedHashMap<>(schema);
        boolean rewriteRefs = false;
        if (root.containsKey("definitions") && !root.containsKey("$defs")) {
            root.put("$defs", root.remove("definitions"));
            rewriteRefs = true;
        }
        if (root.get("additionalProperties") instanceof Map) {
            root.put("additionalProperties", true);
        }
        root.keySet().retainAll(ROOT_KEYS);
        root.values().removeIf(v -> v == null);
        root.put("type", "object");
        if (rewriteRefs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rewritten = (Map<String, Object>) rewriteRefs(root);
            return rewritten;
        }
        return root;
    }

    private static Object rewriteRefs(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if ("$ref".equals(k) && v instanceof String ref && ref.startsWith("#/definitions/")) {
                    copy.put("$ref", "#/$defs/" + ref.substring("#/definitions/".length()));
                } else {
                    copy.put(String.valueOf(k), rewriteRefs(v));
                }
            });
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(rewriteRefs(item)));
            return copy;
        }
        return node;
    }
}
