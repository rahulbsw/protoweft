package io.github.rahulbsw.protoweft.agui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * RFC 6902 JSON Patch over plain JSON values ({@code Map}, {@code List}, scalars), with RFC 6901
 * pointers. Used for {@code STATE_DELTA} and {@code ACTIVITY_DELTA}.
 */
public final class JsonPatch {

    private static final Pattern POINTER = Pattern.compile("^(/([^/~]|~[01])*)*$");

    private JsonPatch() {
    }

    /** A patch that fails to apply; the target document is left unchanged. */
    public static final class PatchException extends RuntimeException {
        public PatchException(String message) {
            super(message);
        }
    }

    /**
     * Applies {@code patch} to a copy of {@code document} and returns the result. Atomic: on any
     * failure (including a failed {@code test}) the input is untouched and {@link PatchException}
     * is thrown.
     */
    public static Object apply(Object document, List<?> patch) {
        Object doc = Json.copy(document);
        for (int i = 0; i < patch.size(); i++) {
            if (!(patch.get(i) instanceof Map<?, ?> op)) {
                throw new PatchException("operation " + i + " is not an object");
            }
            try {
                doc = applyOne(doc, op);
            } catch (PatchException e) {
                throw new PatchException("operation " + i + " (" + op.get("op") + "): " + e.getMessage());
            }
        }
        return doc;
    }

    /**
     * Checks a patch's structure. A malformed operation is fatal; one whose {@code op} RFC 6902 does
     * not define is dropped with a warning; members RFC 6902 does not define are kept.
     */
    static List<Object> read(Decoder d, List<Object> patch, String path) {
        List<Object> out = new ArrayList<>(patch.size());
        for (int i = 0; i < patch.size(); i++) {
            String p = path + "[" + i + "]";
            if (!(patch.get(i) instanceof Map<?, ?> op)) {
                throw new AgUiFormatException(p + " must be an object");
            }
            if (!(op.get("op") instanceof String name)) {
                throw new AgUiFormatException(p + ".op must be a string");
            }
            switch (name) {
                case "add", "replace", "test" -> {
                    pointer(op, "path", p);
                    if (!op.containsKey("value")) {
                        throw new AgUiFormatException(p + ".value is required");
                    }
                }
                case "remove" -> pointer(op, "path", p);
                case "move", "copy" -> {
                    pointer(op, "path", p);
                    pointer(op, "from", p);
                }
                default -> {
                    d.warn("dropped " + p + ": unrecognised op '" + name + "'");
                    continue;
                }
            }
            out.add(op);
        }
        return out;
    }

    private static void pointer(Map<?, ?> op, String field, String path) {
        if (!(op.get(field) instanceof String s) || !POINTER.matcher(s).matches()) {
            throw new AgUiFormatException(path + "." + field + " must be a JSON Pointer");
        }
    }

    private static Object applyOne(Object doc, Map<?, ?> op) {
        String name = String.valueOf(op.get("op"));
        List<String> path = parse(op.get("path"));
        return switch (name) {
            case "add" -> add(doc, path, Json.copy(required(op, "value")));
            case "remove" -> {
                get(doc, path);
                yield remove(doc, path);
            }
            case "replace" -> {
                get(doc, path);
                yield path.isEmpty() ? Json.copy(required(op, "value"))
                        : add(remove(doc, path), path, Json.copy(required(op, "value")));
            }
            case "move" -> {
                List<String> from = parse(op.get("from"));
                if (path.size() > from.size() && path.subList(0, from.size()).equals(from)) {
                    throw new PatchException("cannot move a value into one of its own children");
                }
                Object value = get(doc, from);
                yield add(remove(doc, from), path, value);
            }
            case "copy" -> add(doc, path, Json.copy(get(doc, parse(op.get("from")))));
            case "test" -> {
                if (!equal(get(doc, path), required(op, "value"))) {
                    throw new PatchException("test failed at " + op.get("path"));
                }
                yield doc;
            }
            default -> throw new PatchException("unknown op");
        };
    }

    private static Object required(Map<?, ?> op, String field) {
        if (!op.containsKey(field)) {
            throw new PatchException("missing '" + field + "'");
        }
        return op.get(field);
    }

    static List<String> parse(Object pointer) {
        if (!(pointer instanceof String s) || !POINTER.matcher(s).matches()) {
            throw new PatchException("invalid JSON Pointer: " + pointer);
        }
        List<String> tokens = new ArrayList<>();
        if (s.isEmpty()) {
            return tokens;
        }
        for (String raw : s.substring(1).split("/", -1)) {
            tokens.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return tokens;
    }

    private static Object get(Object doc, List<String> path) {
        Object current = doc;
        for (String token : path) {
            if (current instanceof Map<?, ?> m) {
                if (!m.containsKey(token)) {
                    throw new PatchException("no member '" + token + "'");
                }
                current = m.get(token);
            } else if (current instanceof List<?> l) {
                current = l.get(index(token, l.size(), false));
            } else {
                throw new PatchException("cannot descend into a scalar at '" + token + "'");
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Object add(Object doc, List<String> path, Object value) {
        if (path.isEmpty()) {
            return value;
        }
        Object parent = get(doc, path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        if (parent instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).put(last, value);
        } else if (parent instanceof List<?> l) {
            List<Object> list = (List<Object>) l;
            list.add(last.equals("-") ? list.size() : index(last, list.size(), true), value);
        } else {
            throw new PatchException("parent of '" + last + "' is not a container");
        }
        return doc;
    }

    private static Object remove(Object doc, List<String> path) {
        if (path.isEmpty()) {
            return null;
        }
        Object parent = get(doc, path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        if (parent instanceof Map<?, ?> m) {
            m.remove(last);
        } else if (parent instanceof List<?> l) {
            l.remove(index(last, l.size(), false));
        } else {
            throw new PatchException("parent of '" + last + "' is not a container");
        }
        return doc;
    }

    private static int index(String token, int size, boolean forInsert) {
        if (!token.matches("0|[1-9][0-9]{0,8}")) {
            throw new PatchException("invalid array index '" + token + "'");
        }
        int i = Integer.parseInt(token);
        if (i > size || (!forInsert && i == size)) {
            throw new PatchException("array index " + i + " out of bounds");
        }
        return i;
    }

    /** JSON equality: numbers compare by value, objects ignore member order. */
    static boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            try {
                return new java.math.BigDecimal(x.toString()).compareTo(new java.math.BigDecimal(y.toString())) == 0;
            } catch (NumberFormatException nonFinite) {
                return x.doubleValue() == y.doubleValue();
            }
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (Object k : x.keySet()) {
                if (!equal(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!equal(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    /** Builds one operation, for producers. */
    public static Map<String, Object> op(String op, String path, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("path", path);
        if (!op.equals("remove")) {
            m.put("value", value);
        }
        return m;
    }
}
