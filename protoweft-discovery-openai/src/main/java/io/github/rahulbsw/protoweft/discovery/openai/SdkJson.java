package io.github.rahulbsw.protoweft.discovery.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON conversions through the OpenAI SDK's own mapper, so values match what the SDK sends. */
final class SdkJson {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private SdkJson() {
    }

    /** Each entry as an SDK {@link JsonValue}, keeping key order. */
    static Map<String, JsonValue> values(Map<String, Object> map) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, JsonValue.from(v)));
        return out;
    }

    /** Parses a JSON object (blank means no arguments). */
    static Map<String, Object> object(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = ObjectMappers.jsonMapper().readValue(json, OBJECT);
            if (parsed == null) {
                throw new IllegalArgumentException("expected a JSON object, got null");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
    }

    /** A JSON object value as plain maps; anything else is an empty map. */
    static Map<String, Object> object(JsonValue value) {
        if (value == null || value.asObject().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> converted = value.convert(OBJECT);
        return converted == null ? Map.of() : converted;
    }

    static String write(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON-serializable: " + e.getOriginalMessage(), e);
        }
    }
}
