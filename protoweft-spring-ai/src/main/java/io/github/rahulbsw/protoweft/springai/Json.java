package io.github.rahulbsw.protoweft.springai;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Jackson 3 (the version Spring AI 2 uses) between JSON text and plain maps/lists/scalars. */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

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

    /** Parses any JSON value, or returns {@code null} if the text is not JSON. */
    static Object parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            return null;
        }
    }
}
