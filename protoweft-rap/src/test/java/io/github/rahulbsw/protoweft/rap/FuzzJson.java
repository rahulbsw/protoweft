package io.github.rahulbsw.protoweft.rap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a fuzzer input into JSON documents, in one of two modes.
 *
 * <ul>
 *   <li>Raw mode (the input does not start with a NUL byte): the input is the document, or for
 *       {@link #documents} one document per line, so seed files stay readable JSON and the parser
 *       sees arbitrary bytes.
 *   <li>Structured mode (a leading NUL, which no JSON text starts with): the remaining bytes pick a
 *       valid template and apply a few edits to it (replace, remove or add a member or element,
 *       with values that are mostly {@code words}, nulls, booleans and edge-case numbers), so the
 *       fuzzer reaches type and null confusions deep inside otherwise valid messages instead of
 *       spending its time on JSON syntax.
 * </ul>
 *
 * <p>The same copy lives in each module that fuzzes JSON (test sources aren't shared between modules).
 */
final class FuzzJson {

    private static final String[] NUMBERS = {"0", "1", "-1", "2", "255", "-0", "0.5", "1e400", "-1e-400",
        "2147483647", "2147483648", "-2147483649", "9223372036854775807", "9223372036854775808",
        "123456789012345678901234567890"};

    private final byte[] data;
    private final List<String> words;
    private int pos = 1;

    private FuzzJson(byte[] data, List<String> words) {
        this.data = data;
        this.words = words;
    }

    /** A template object with members in the given order ({@code Map.of} order varies between runs). */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    /** One document. */
    static byte[] document(byte[] input, List<String> words, List<Object> templates) {
        if (input.length == 0 || input[0] != 0) {
            return input;
        }
        return new FuzzJson(input, words).edited(templates);
    }

    /** One or more documents: lines in raw mode, one to four edited templates in structured mode. */
    static List<byte[]> documents(byte[] input, List<String> words, List<Object> templates) {
        List<byte[]> out = new ArrayList<>();
        if (input.length == 0 || input[0] != 0) {
            int start = 0;
            for (int i = 0; i <= input.length; i++) {
                if (i == input.length || input[i] == '\n') {
                    out.add(Arrays.copyOfRange(input, start, i));
                    start = i + 1;
                }
            }
            return out;
        }
        FuzzJson gen = new FuzzJson(input, words);
        for (int n = 1 + gen.next() % 4; n > 0; n--) {
            out.add(gen.edited(templates));
        }
        return out;
    }

    private byte[] edited(List<Object> templates) {
        Object doc = copy(templates.get(next() % templates.size()));
        for (int n = next() % 5; n > 0; n--) {
            List<Object> containers = new ArrayList<>();
            collect(doc, containers);
            if (containers.isEmpty()) {
                break;
            }
            edit(containers.get(next() % containers.size()), next() % 3);
        }
        StringBuilder sb = new StringBuilder();
        write(doc, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void edit(Object container, int op) {
        if (container instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            List<String> keys = new ArrayList<>(map.keySet());
            if (op == 2 || keys.isEmpty()) {
                map.put((next() & 7) == 0 ? rawString() : word(), value(0));
            } else if (op == 1) {
                map.remove(keys.get(next() % keys.size()));
            } else {
                map.put(keys.get(next() % keys.size()), value(0));
            }
        } else {
            List<Object> list = (List<Object>) container;
            if (op == 2 || list.isEmpty()) {
                list.add(Math.min(list.size(), next() % (list.size() + 1)), value(0));
            } else if (op == 1) {
                list.remove(next() % list.size());
            } else {
                list.set(next() % list.size(), value(0));
            }
        }
    }

    private int next() {
        return pos < data.length ? data[pos++] & 0xFF : 0;
    }

    private Object value(int depth) {
        switch (next() % (depth >= 2 ? 6 : 8)) {
            case 0:
                return null;
            case 1:
                return (next() & 1) == 0;
            case 2:
                return new Literal(NUMBERS[next() % NUMBERS.length]);
            case 3:
            case 4:
                return word();
            case 5:
                return rawString();
            case 6: {
                List<Object> list = new ArrayList<>();
                for (int n = next() % 4; n > 0; n--) {
                    list.add(value(depth + 1));
                }
                return list;
            }
            default: {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int n = next() % 4; n > 0; n--) {
                    map.put(word(), value(depth + 1));
                }
                return map;
            }
        }
    }

    private String word() {
        return words.get(next() % words.size());
    }

    private String rawString() {
        int start = Math.min(pos, data.length);
        int end = Math.min(data.length, start + next() % 24);
        pos = Math.max(pos, end);
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put((String) k, copy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(copy(v)));
            return out;
        }
        return value;
    }

    private static void collect(Object value, List<Object> out) {
        if (value instanceof Map<?, ?> map) {
            out.add(map);
            map.values().forEach(v -> collect(v, out));
        } else if (value instanceof List<?> list) {
            out.add(list);
            list.forEach(v -> collect(v, out));
        }
    }

    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                sb.append(i == 0 ? "" : ",");
                write(list.get(i), sb);
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(first ? "" : ",");
                first = false;
                write(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else {
            sb.append(value); // Boolean, Number, Literal
        }
    }

    /** A number, written verbatim. */
    private record Literal(String text) {
        @Override
        public String toString() {
            return text;
        }
    }
}
