package io.agentweave.evals.discovery.metrics;

import io.agentweave.evals.discovery.Json;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic AST-style argument matching of a predicted call against a gold call — the
 * <b>call-correctness</b> proxy for task completion (tools are not executed).
 *
 * <p>A predicted argument map {@code P} <b>matches</b> gold {@code G} for a tool whose input
 * schema is {@code S} when all of these hold:
 * <ol>
 *   <li>every key of {@code G} with a non-null value is in {@code P} with an equal value (below);
 *       a gold {@code null} requires the key to be absent or null;</li>
 *   <li>every name in {@code S.required} is present and non-null in {@code P};</li>
 *   <li>every key of {@code P} that is not in {@code G} is a property declared in {@code S}
 *       (unless {@code S} declares no properties or allows {@code additionalProperties}); its value
 *       is not compared — the model may fill optional parameters the label omits.</li>
 * </ol>
 * Value equality, recursively:
 * <ul>
 *   <li>numbers (or numeric strings against numbers): numerically equal ({@code 5} = {@code 5.0} = {@code "5"});</li>
 *   <li>booleans (or {@code "true"/"false"} strings against booleans): equal;</li>
 *   <li>strings: equal after Unicode NFKC, trim, whitespace collapse and lower-casing;</li>
 *   <li>lists: same length, element-wise equal, order significant;</li>
 *   <li>objects: rule 1 applied recursively (gold keys must match, extra keys allowed);</li>
 *   <li>other mixed types: equal string forms after the string normalization.</li>
 * </ul>
 * Free-text arguments (search queries, messages) that the model paraphrases fail rule 1, so this
 * is a strict lower bound; MCPToolBench++ itself uses an LLM judge here. {@link #keysMatch}
 * is the lenient companion (rules 1–3 with values ignored), an upper bound.
 */
public final class ArgumentMatcher {

    private ArgumentMatcher() {
    }

    /** Result with human-readable reasons for a mismatch (empty when matched). */
    public record Result(boolean matched, List<String> reasons) {
    }

    public static Result match(Map<String, Object> gold, Map<String, Object> predicted, Map<String, Object> schema) {
        return check(gold, predicted, schema, true);
    }

    /** Rules 1–3 without value comparison. */
    public static Result keysMatch(Map<String, Object> gold, Map<String, Object> predicted, Map<String, Object> schema) {
        return check(gold, predicted, schema, false);
    }

    private static Result check(Map<String, Object> gold, Map<String, Object> predicted, Map<String, Object> schema,
                                boolean compareValues) {
        Map<String, Object> p = predicted == null ? Map.of() : predicted;
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<String, Object> g : gold.entrySet()) {
            Object pv = p.get(g.getKey());
            if (g.getValue() == null) {
                if (pv != null) {
                    reasons.add("unexpected value for " + g.getKey());
                }
            } else if (pv == null) {
                reasons.add("missing " + g.getKey());
            } else if (compareValues && !valuesEqual(g.getValue(), pv)) {
                reasons.add("value differs for " + g.getKey());
            }
        }
        Map<String, Object> s = schema == null ? Map.of() : schema;
        for (Object r : Json.asList(s.get("required"))) {
            if (p.get(String.valueOf(r)) == null && !reasons.contains("missing " + r)) {
                reasons.add("missing required " + r);
            }
        }
        Map<String, Object> props = Json.asMap(s.get("properties"));
        boolean open = props.isEmpty() || Boolean.TRUE.equals(s.get("additionalProperties"))
                || s.get("additionalProperties") instanceof Map;
        if (!open) {
            Set<String> declared = props.keySet();
            for (String k : p.keySet()) {
                if (!gold.containsKey(k) && !declared.contains(k)) {
                    reasons.add("undeclared argument " + k);
                }
            }
        }
        return new Result(reasons.isEmpty(), List.copyOf(reasons));
    }

    /** Value equality as documented on the class. */
    public static boolean valuesEqual(Object gold, Object predicted) {
        if (gold == null || predicted == null) {
            return gold == predicted;
        }
        if (gold instanceof Number || predicted instanceof Number) {
            BigDecimal a = number(gold);
            BigDecimal b = number(predicted);
            return a != null && b != null && a.compareTo(b) == 0;
        }
        if (gold instanceof Boolean || predicted instanceof Boolean) {
            Boolean a = bool(gold);
            Boolean b = bool(predicted);
            return a != null && a.equals(b);
        }
        if (gold instanceof List<?> gl) {
            if (!(predicted instanceof List<?> pl) || gl.size() != pl.size()) {
                return false;
            }
            for (int i = 0; i < gl.size(); i++) {
                if (!valuesEqual(gl.get(i), pl.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (gold instanceof Map<?, ?> gm) {
            if (!(predicted instanceof Map<?, ?> pm)) {
                return false;
            }
            for (Map.Entry<?, ?> e : gm.entrySet()) {
                Object pv = pm.get(e.getKey());
                if (e.getValue() == null ? pv != null : !valuesEqual(e.getValue(), pv)) {
                    return false;
                }
            }
            return true;
        }
        if (predicted instanceof List || predicted instanceof Map) {
            return false;
        }
        return normalize(gold.toString()).equals(normalize(predicted.toString()));
    }

    /** NFKC, trim, collapse whitespace, lower-case. */
    public static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static BigDecimal number(Object v) {
        if (v instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String t = s.strip().toLowerCase(Locale.ROOT);
            return t.equals("true") ? Boolean.TRUE : t.equals("false") ? Boolean.FALSE : null;
        }
        return null;
    }
}
