package io.github.rahulbsw.protoweft.agui;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson 3 behind a plain-value API: objects are {@code Map}, arrays {@code List}. Decimals are read
 * as {@link java.math.BigDecimal} and large integers as {@link java.math.BigInteger}, so every number a
 * peer sends survives a round trip exactly (a {@code double} would turn {@code 1e400} into infinity).
 */
final class Json {

    /** Nesting deeper than this is rejected, so hostile input cannot exhaust the stack. */
    static final int MAX_DEPTH = 200;

    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private Json() {
    }

    /** @throws AgUiFormatException if {@code text} is not one JSON value */
    static Object parse(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (RuntimeException e) {
            throw new AgUiFormatException("not valid JSON");
        }
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

    /**
     * A deep, mutable copy of a plain JSON value, so callers cannot change what was recorded.
     *
     * @throws IllegalArgumentException for a non-finite number, which JSON cannot represent
     */
    @SuppressWarnings("unchecked")
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, copy(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(copy(v)));
            return out;
        }
        if ((value instanceof Double d && !Double.isFinite(d)) || (value instanceof Float f && !Float.isFinite(f))) {
            throw new IllegalArgumentException("JSON cannot represent " + value);
        }
        return value;
    }
}
