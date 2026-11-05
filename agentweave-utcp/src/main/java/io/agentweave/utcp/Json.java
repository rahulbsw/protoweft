package io.agentweave.utcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Jackson 3 behind a tiny plain-value API (maps, lists, strings, numbers, booleans, null). */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** @throws JacksonException if {@code text} is not JSON */
    static Object parse(String text) {
        return MAPPER.readValue(text, Object.class);
    }

    static Object parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static byte[] writeBytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    /** Text form of an argument value: strings as-is, everything else as JSON. */
    static String text(Object value) {
        return value instanceof String s ? s : write(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : null;
    }
}
