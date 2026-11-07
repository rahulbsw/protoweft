package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated toolset manifest ({@code /.well-known/rap-toolset}) and the mapping between RAP
 * {@code annotations} and {@link ToolSpec#metadata()}, shared by client and server.
 *
 * @param serverUrl the base URL it was fetched from
 * @param name      the toolset name
 * @param endpoint  where invocations go
 * @param tools     tool name → spec, in manifest order
 */
record Toolset(String serverUrl, String name, URI endpoint, Map<String, ToolSpec> tools) {

    /** Allowed tool and toolset name characters (toolsets.md "Tool Names"). */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    /** Neutral metadata travels as namespaced annotation keys (toolsets.md "Annotations"). */
    static final String ANNOTATION_PREFIX = "x-protoweft-";

    private static final List<String> NEUTRAL_KEYS = List.of(ToolSpec.META_READ_ONLY, ToolSpec.META_DESTRUCTIVE,
            ToolSpec.META_IDEMPOTENT, ToolSpec.META_OPEN_WORLD, ToolSpec.META_TITLE, ToolSpec.META_TAGS);

    /**
     * Parses and validates a manifest; any violation rejects the whole toolset.
     *
     * @throws IllegalArgumentException describing the first violation
     */
    static Toolset parse(String serverUrl, byte[] body) {
        Map<String, Object> m = Json.parseObject(body);
        String name = Json.requireString(m, "name");
        if (name.length() > 128) {
            throw new IllegalArgumentException("toolset 'name' must be 1 to 128 characters");
        }
        Json.optionalString(m, "description");
        URI endpoint = httpUrl(Json.requireString(m, "endpoint"), "endpoint");
        Json.optionalBoolean(m, "needsMigration");
        List<Object> tools = Json.array(m.get("tools"));
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("'tools' must be an array with at least one tool");
        }
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        for (Object entry : tools) {
            Map<String, Object> t = Json.object(entry);
            if (t == null) {
                throw new IllegalArgumentException("every tool must be an object");
            }
            String toolName = Json.requireString(t, "name");
            if (!NAME.matcher(toolName).matches()) {
                throw new IllegalArgumentException("tool name must be 1 to 128 characters of [A-Za-z0-9_-]: "
                        + toolName);
            }
            String description = Json.requireText(t, "description");
            Map<String, Object> schema = Json.object(t.get("inputSchema"));
            if (schema == null) {
                throw new IllegalArgumentException("tool '" + toolName + "': 'inputSchema' must be a JSON object");
            }
            Object annotations = t.get("annotations");
            if (annotations != null && Json.object(annotations) == null) {
                throw new IllegalArgumentException("tool '" + toolName + "': 'annotations' must be an object");
            }
            if (specs.put(toolName, new ToolSpec(toolName, description, schema,
                    metadataFrom(Json.object(annotations)))) != null) {
                throw new IllegalArgumentException("duplicate tool name in toolset: " + toolName);
            }
        }
        return new Toolset(serverUrl, name, endpoint, Map.copyOf(specs));
    }

    /** Annotations kept whole under the RAP key, and namespaced neutral keys mapped back. */
    static Map<String, Object> metadataFrom(Map<String, Object> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : NEUTRAL_KEYS) {
            Object v = annotations.get(ANNOTATION_PREFIX + key);
            if (v != null) {
                metadata.put(key, v);
            }
        }
        metadata.put(RapClientConnector.META_ANNOTATIONS, annotations);
        return metadata;
    }

    /** The annotations for a spec: its RAP annotations plus namespaced neutral metadata, or {@code null}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> annotationsFor(ToolSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec.metadata().get(RapClientConnector.META_ANNOTATIONS) instanceof Map<?, ?> raw) {
            out.putAll((Map<String, Object>) raw);
        }
        for (String key : NEUTRAL_KEYS) {
            Object v = spec.metadata().get(key);
            if (v != null) {
                out.put(ANNOTATION_PREFIX + key, v);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Tool names defined by more than one toolset (toolsets.md "Loading Toolsets"). */
    static Set<String> collisions(List<Toolset> toolsets) {
        Set<String> seen = new HashSet<>();
        Set<String> dup = new HashSet<>();
        for (Toolset t : toolsets) {
            for (String n : t.tools().keySet()) {
                if (!seen.add(n)) {
                    dup.add(n);
                }
            }
        }
        return dup;
    }

    static URI httpUrl(String value, String field) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("'" + field + "' is not a valid URL: " + value);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("'" + field + "' must be an absolute http(s) URL: " + value);
        }
        return uri;
    }
}
