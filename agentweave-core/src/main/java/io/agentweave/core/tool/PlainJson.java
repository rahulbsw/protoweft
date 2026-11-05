package io.agentweave.core.tool;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Serializes plain values (maps, lists, strings, numbers, booleans, null) to JSON text. Used
 * to give structured tool results a text form without a JSON library in the core.
 */
final class PlainJson {

    private PlainJson() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(s, out);
        } else if (value instanceof Double d && (d.isNaN() || d.isInfinite())
                || value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            out.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> m) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                string(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
                if (it.hasNext()) {
                    out.append(',');
                }
            }
            out.append('}');
        } else if (value instanceof Collection<?> c) {
            out.append('[');
            Iterator<?> it = c.iterator();
            while (it.hasNext()) {
                write(it.next(), out);
                if (it.hasNext()) {
                    out.append(',');
                }
            }
            out.append(']');
        } else {
            string(value.toString(), out);
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
