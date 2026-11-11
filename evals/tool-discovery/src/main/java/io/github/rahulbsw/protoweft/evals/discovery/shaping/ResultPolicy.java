package io.github.rahulbsw.protoweft.evals.discovery.shaping;

import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes a mocked tool result before it is fed back to the model — the result-side half of the
 * study's factorial. Each policy trades fewer result tokens for a risk that the model re-asks for
 * what was trimmed (the re-fetch rate measures that cost). Names are stable so arms and reports can
 * reference them: {@code raw}, {@code truncate=<n>}, {@code project}, {@code byref}.
 */
public interface ResultPolicy {

    String name();

    /** The result as it should enter the model's context. */
    ToolResult shape(ToolResult raw, TokenEstimator estimator);

    /** Pass the result through unchanged. */
    static ResultPolicy raw() {
        return new ResultPolicy() {
            public String name() {
                return "raw";
            }

            public ToolResult shape(ToolResult r, TokenEstimator est) {
                return r;
            }
        };
    }

    /** Cut the result to at most {@code maxTokens} and append a "more available" marker. */
    static ResultPolicy truncate(int maxTokens) {
        return new ResultPolicy() {
            public String name() {
                return "truncate=" + maxTokens;
            }

            public ToolResult shape(ToolResult r, TokenEstimator est) {
                String content = r.content();
                long tokens = est.text(content);
                if (tokens <= maxTokens) {
                    return r;
                }
                int keep = (int) Math.max(1, content.length() * ((double) maxTokens / tokens));
                String cut = content.substring(0, Math.min(keep, content.length()));
                String marker = " …[truncated: " + (tokens - maxTokens) + " more tokens; call again to continue]";
                return new ToolResult(r.callId(), cut + marker, r.error());
            }
        };
    }

    /**
     * Keep only scalar fields of a JSON object (ids, counts, flags, short strings); replace large
     * strings and nested arrays/objects with a {@code {"_elided": "<n> items"}} placeholder. Falls
     * back to {@link #truncate(int) truncate} when the content is not a JSON object.
     */
    static ResultPolicy project(int scalarCharCap) {
        return new ResultPolicy() {
            public String name() {
                return "project";
            }

            public ToolResult shape(ToolResult r, TokenEstimator est) {
                Object parsed;
                try {
                    parsed = Json.parse(r.content());
                } catch (RuntimeException e) {
                    return truncate(scalarCharCap).shape(r, est);
                }
                if (!(parsed instanceof Map<?, ?> obj)) {
                    return truncate(scalarCharCap).shape(r, est);
                }
                Map<String, Object> kept = new LinkedHashMap<>();
                obj.forEach((k, v) -> kept.put(String.valueOf(k), projectValue(v, scalarCharCap)));
                return new ToolResult(r.callId(), Json.write(kept), r.error());
            }
        };
    }

    private static Object projectValue(Object v, int cap) {
        if (v instanceof Map<?, ?> m) {
            return Map.of("_elided", m.size() + " fields");
        }
        if (v instanceof List<?> l) {
            return Map.of("_elided", l.size() + " items");
        }
        if (v instanceof String s && s.length() > cap) {
            return s.substring(0, cap) + "…";
        }
        return v;
    }

    /**
     * Replace the result with an out-of-band handle plus a short summary and its real size, so the
     * payload stays out of context. The model may dereference it (re-fetch) if it needs the body.
     */
    static ResultPolicy byReference(int summaryChars) {
        return new ResultPolicy() {
            public String name() {
                return "byref";
            }

            public ToolResult shape(ToolResult r, TokenEstimator est) {
                String content = r.content();
                String summary = content.length() <= summaryChars ? content : content.substring(0, summaryChars) + "…";
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("ref", "res://" + r.callId());
                ref.put("tokens", est.text(content));
                ref.put("summary", summary);
                return new ToolResult(r.callId(), Json.write(ref), r.error());
            }
        };
    }

    /** Parses a policy spec: {@code raw}, {@code truncate=<n>}, {@code project}, {@code byref}. */
    static ResultPolicy parse(String spec) {
        String s = spec == null ? "raw" : spec.strip();
        int eq = s.indexOf('=');
        String kind = eq < 0 ? s : s.substring(0, eq);
        String arg = eq < 0 ? null : s.substring(eq + 1).strip();
        return switch (kind) {
            case "raw" -> raw();
            case "truncate" -> truncate(arg == null ? 256 : Integer.parseInt(arg));
            case "project" -> project(arg == null ? 120 : Integer.parseInt(arg));
            case "byref" -> byReference(arg == null ? 160 : Integer.parseInt(arg));
            default -> throw new IllegalArgumentException("unknown result policy: " + spec
                    + " (raw|truncate=<n>|project|byref)");
        };
    }
}
