package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonicalizes tool-call arguments to a stable string key, so a recorded fixture replays
 * regardless of the order the model emits object keys in. Maps are sorted by key recursively;
 * lists keep their order (order is meaningful in arguments). The canonical JSON string is the key.
 */
public final class NormalizedArgs {

    private NormalizedArgs() {
    }

    /** A stable key for {@code tool} plus its normalized arguments. */
    public static String key(String tool, Map<String, Object> arguments) {
        return tool + '\u0000' + Json.write(canonical(arguments));
    }

    /** Arguments with every nested object's keys sorted; lists untouched. */
    public static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(NormalizedArgs::canonical).toList();
        }
        return value;
    }
}
