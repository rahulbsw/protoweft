package io.github.rahulbsw.protoweft.agui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Reads one JSON object under the spec's processing model: a member the schema does not describe is
 * stripped with a warning, a described member with a value the schema rejects is fatal
 * ({@link AgUiFormatException}). An optional member sent as {@code null} is read as absent, with a
 * warning: the spec forbids sending it, and treating it as absent is the compatibility tolerance the
 * spec allows for peers that do.
 */
final class Decoder {

    /** The largest integer the schema allows: 2^53 - 1, exact in every JSON implementation. */
    static final long MAX_SAFE = 9_007_199_254_740_991L;

    private final List<String> warnings;

    Decoder(List<String> warnings) {
        this.warnings = warnings;
    }

    Decoder() {
        this(new ArrayList<>());
    }

    List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    void warn(String warning) {
        warnings.add(warning);
    }

    /** The object at {@code path}, after stripping members not in {@code known}. */
    Obj object(Object value, String path, Set<String> known) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new AgUiFormatException(path + " must be an object");
        }
        Map<String, Object> members = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (known.contains(key)) {
                members.put(key, e.getValue());
            } else {
                warn("stripped unrecognised member " + path + "." + key);
            }
        }
        return new Obj(members, path);
    }

    /** One object's members, read by name. */
    final class Obj {
        private final Map<String, Object> members;
        private final String path;

        Obj(Map<String, Object> members, String path) {
            this.members = members;
            this.path = path;
        }

        String path() {
            return path;
        }

        boolean has(String field) {
            return members.get(field) != null;
        }

        /** The raw value, or {@code null} when absent; a present {@code null} counts as absent (warned). */
        Object optional(String field) {
            if (members.containsKey(field) && members.get(field) == null) {
                warn(path + "." + field + " was null; treated as absent");
            }
            return members.get(field);
        }

        /** A required member's raw value, which may itself be {@code null} where the schema allows any value. */
        Object requiredAny(String field) {
            if (!members.containsKey(field)) {
                throw new AgUiFormatException(path + "." + field + " is required");
            }
            return members.get(field);
        }

        String string(String field) {
            Object v = requiredAny(field);
            if (!(v instanceof String s)) {
                throw new AgUiFormatException(path + "." + field + " must be a string");
            }
            return s;
        }

        String optString(String field) {
            Object v = optional(field);
            if (v == null) {
                return null;
            }
            if (!(v instanceof String s)) {
                throw new AgUiFormatException(path + "." + field + " must be a string");
            }
            return s;
        }

        Boolean optBoolean(String field) {
            Object v = optional(field);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Boolean b)) {
                throw new AgUiFormatException(path + "." + field + " must be a boolean");
            }
            return b;
        }

        /**
         * An optional integer within {@code [min, max]}. As in JSON Schema, a number with a zero
         * fractional part ({@code 10.0}) is an integer.
         */
        Long optInteger(String field, long min, long max) {
            Object v = optional(field);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Number n)) {
                throw new AgUiFormatException(path + "." + field + " must be an integer");
            }
            java.math.BigDecimal exact;
            try {
                exact = new java.math.BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                throw new AgUiFormatException(path + "." + field + " must be an integer");
            }
            if (exact.signum() != 0 && exact.stripTrailingZeros().scale() > 0) {
                throw new AgUiFormatException(path + "." + field + " must be an integer");
            }
            if (exact.compareTo(java.math.BigDecimal.valueOf(min)) < 0 || exact.compareTo(java.math.BigDecimal.valueOf(max)) > 0) {
                throw new AgUiFormatException(path + "." + field + " must be between " + min + " and " + max);
            }
            return exact.longValueExact();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> optObject(String field) {
            Object v = optional(field);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Map<?, ?> m)) {
                throw new AgUiFormatException(path + "." + field + " must be an object");
            }
            return (Map<String, Object>) m;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> object(String field) {
            Object v = requiredAny(field);
            if (!(v instanceof Map<?, ?> m)) {
                throw new AgUiFormatException(path + "." + field + " must be an object");
            }
            return (Map<String, Object>) m;
        }

        /** A required array. */
        List<Object> array(String field) {
            Object v = requiredAny(field);
            if (!(v instanceof List<?> l)) {
                throw new AgUiFormatException(path + "." + field + " must be an array");
            }
            return new ArrayList<>(l);
        }

        /** An optional array, or {@code null}. */
        List<Object> optArray(String field) {
            Object v = optional(field);
            if (v == null) {
                return null;
            }
            if (!(v instanceof List<?> l)) {
                throw new AgUiFormatException(path + "." + field + " must be an array");
            }
            return new ArrayList<>(l);
        }

        List<String> optStrings(String field) {
            List<Object> raw = optArray(field);
            if (raw == null) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                if (!(raw.get(i) instanceof String s)) {
                    throw new AgUiFormatException(path + "." + field + "[" + i + "] must be a string");
                }
                out.add(s);
            }
            return out;
        }

        /** A string or a list of content parts (user and tool message content, tool results). */
        Object stringOrParts(String field) {
            Object v = requiredAny(field);
            if (v instanceof String) {
                return v;
            }
            if (v instanceof List<?>) {
                return items(field, (item, p) -> Codec.contentPart(Decoder.this, item, p));
            }
            throw new AgUiFormatException(path + "." + field + " must be a string or an array of content parts");
        }

        /** Reads each element of a required array; a {@code null} result drops that element. */
        <T> List<T> items(String field, BiFunction<Object, String, T> reader) {
            return read(array(field), field, reader);
        }

        /** As {@link #items} for an optional array; {@code null} when absent. */
        <T> List<T> optItems(String field, BiFunction<Object, String, T> reader) {
            List<Object> raw = optArray(field);
            return raw == null ? null : read(raw, field, reader);
        }

        private <T> List<T> read(List<Object> raw, String field, BiFunction<Object, String, T> reader) {
            List<T> out = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                T item = reader.apply(raw.get(i), path + "." + field + "[" + i + "]");
                if (item != null) {
                    out.add(item);
                }
            }
            return out;
        }
    }
}
