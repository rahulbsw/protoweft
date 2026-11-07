package io.github.rahulbsw.protoweft.rap;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Jackson 3 behind a tiny plain-value API, plus the field checks RAP message validation needs. */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** @throws IllegalArgumentException if {@code bytes} is not JSON */
    static Object parse(byte[] bytes) {
        try {
            return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("body is not valid JSON");
        }
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static byte[] writeBytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : null;
    }

    /** A JSON object body, or {@link IllegalArgumentException}. */
    static Map<String, Object> parseObject(byte[] bytes) {
        Map<String, Object> obj = object(parse(bytes));
        if (obj == null) {
            throw new IllegalArgumentException("body must be a JSON object");
        }
        return obj;
    }

    /** A required non-empty string field. */
    static String requireString(Map<String, Object> obj, String field) {
        if (!(obj.get(field) instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("'" + field + "' must be a non-empty string");
        }
        return s;
    }

    /** A required string field that may be empty. */
    static String requireText(Map<String, Object> obj, String field) {
        if (!(obj.get(field) instanceof String s)) {
            throw new IllegalArgumentException("'" + field + "' must be a string");
        }
        return s;
    }

    /** An optional string field: absent or {@code null} gives {@code null}. */
    static String optionalString(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("'" + field + "' must be a string or null");
        }
        return s;
    }

    /** An optional boolean field: absent or {@code null} gives {@code false}. */
    static boolean optionalBoolean(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        if (v == null) {
            return false;
        }
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException("'" + field + "' must be a boolean");
        }
        return b;
    }

    /** A required non-negative integer field. */
    static int requireIndex(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            long n = ((Number) v).longValue();
            if (n >= 0 && n <= Integer.MAX_VALUE) {
                return (int) n;
            }
        }
        throw new IllegalArgumentException("'" + field + "' must be a non-negative integer");
    }
}
