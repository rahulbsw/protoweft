package io.agentweave.argi.neutral;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Jackson 3 between JSON text (ARGI's tool contract) and plain maps/lists/scalars (AgentWeave's). */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Map<String, Object> EMPTY_OBJECT_SCHEMA = emptyObjectSchema();

    private Json() {
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * Parses a JSON object; blank input is an empty object.
     *
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Object value;
        try {
            value = MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return MAPPER.convertValue(value, MAP);
    }

    /** Parses any JSON value, or returns {@code fallback} if the text is not JSON. */
    static Object parseOr(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            return fallback;
        }
    }

    /** Providers require an object root; an empty schema or one without {@code type} becomes one. */
    static Map<String, Object> objectSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_OBJECT_SCHEMA;
        }
        if (schema.containsKey("type")) {
            return schema;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("type", "object");
        copy.putAll(schema);
        return copy;
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return Collections.unmodifiableMap(schema);
    }
}
