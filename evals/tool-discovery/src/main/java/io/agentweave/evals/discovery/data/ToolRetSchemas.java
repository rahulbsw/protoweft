package io.agentweave.evals.discovery.data;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts ToolRet tool documents (heterogeneous, one format per source benchmark) into a
 * {@link ToolSpec} with a valid JSON Schema ({@code type: object}) input schema.
 *
 * <p>Formats handled (key in the document → interpretation):
 * <ul>
 *   <li>{@code doc_arguments} (ultratool), or {@code parameters} with {@code type: object|dict}
 *       and a {@code properties} map (toolace, rotbench): JSON-Schema-like, normalized;</li>
 *   <li>{@code parameters} map name → {@code {type: "str, optional", description, default}}
 *       (apigen) or name → {@code "Required. string. …"} (toolalpaca);</li>
 *   <li>{@code parameters} list of {@code {name, in, schema{type, default}, description,
 *       required}} (apibank, taskbench, mnms, webtools, tooleyes, gpt4tools, reversechain);
 *       required when {@code required} is true or {@code in} is {@code required} or {@code path};</li>
 *   <li>{@code parameters} string such as {@code "(image: str)"};</li>
 *   <li>{@code required_parameters} / {@code optional_parameters} lists (toolbench, toollens,
 *       t-eval);</li>
 *   <li>{@code inputs} list with an {@code optional} flag (gta);</li>
 *   <li>{@code additional_required_arguments} / {@code optional_arguments} maps keyed
 *       {@code "name (type)"} (appbench);</li>
 *   <li>{@code api_arguments} list of names or name → default map (gorilla), all optional;</li>
 *   <li>anything else: an empty object schema.</li>
 * </ul>
 * Type names map to JSON types: str/text/url/date/image/… → string, int/long → integer,
 * float/double/NUMBER → number, bool → boolean, list/tuple/List[...] → array, dict/object/map
 * → object; a {@code , optional} suffix marks the parameter optional; unknown → string. Only
 * standard JSON Schema keywords are kept; defaults are kept only when they match the type.
 * Property names are made provider-safe ({@link #safeKey}); duplicates after renaming are dropped.
 */
public final class ToolRetSchemas {

    private static final Set<String> KEEP = Set.of("type", "description", "properties", "required", "items",
            "enum", "default", "format", "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength",
            "pattern", "additionalProperties", "anyOf", "oneOf", "allOf", "title", "examples", "const");
    private static final Pattern NAME_TYPE = Pattern.compile("^\\s*([^()]+?)\\s*\\(([^)]*)\\)\\s*$");

    private ToolRetSchemas() {
    }

    /** Builds the tool spec of one ToolRet tool document. */
    public static ToolSpec toSpec(String toolId, Map<String, Object> doc) {
        String name = firstText(doc, "name", "api_name");
        String description = firstText(doc, "description", "func_description", "functionality");
        return new ToolSpec(name == null ? toolId : name, description == null ? "" : description.strip(),
                toJsonSchema(doc));
    }

    /** The JSON Schema for a ToolRet tool document. */
    public static Map<String, Object> toJsonSchema(Map<String, Object> doc) {
        Object params = doc.get("parameters");
        if (doc.get("doc_arguments") instanceof Map<?, ?> m) {
            return objectRoot(normalize(Json.asMap(m)));
        }
        if (params instanceof Map<?, ?> m) {
            Map<String, Object> p = Json.asMap(m);
            if (isSchemaObject(p)) {
                Map<String, Object> schema = normalize(p);
                Map<String, Object> props = Json.asMap(schema.get("properties"));
                if (!schema.containsKey("required") && doc.get("required") instanceof List<?> req) {
                    List<Object> known = req.stream().filter(props::containsKey).map(r -> (Object) r).toList();
                    if (!known.isEmpty()) {
                        schema.put("required", known);
                    }
                }
                return objectRoot(schema);
            }
            return fromParamMap(p);
        }
        if (params instanceof List<?> l) {
            return fromParamList(Json.asList(l));
        }
        if (params instanceof String s && !s.isBlank()) {
            return fromSignature(s);
        }
        if (doc.containsKey("required_parameters") || doc.containsKey("optional_parameters")) {
            Props props = new Props();
            addNamedList(props, doc.get("required_parameters"), true);
            addNamedList(props, doc.get("optional_parameters"), false);
            return props.schema();
        }
        if (doc.get("inputs") instanceof List<?> inputs) {
            Props props = new Props();
            for (Object o : inputs) {
                Map<String, Object> in = Json.asMap(o);
                boolean optional = Boolean.TRUE.equals(in.get("optional"));
                props.add(Json.asString(in.get("name")), in.get("type"), in.get("description"), in.get("default"),
                        !optional);
            }
            return props.schema();
        }
        if (doc.containsKey("additional_required_arguments") || doc.containsKey("optional_arguments")) {
            Props props = new Props();
            addNameTypeMap(props, doc.get("additional_required_arguments"), true);
            addNameTypeMap(props, doc.get("optional_arguments"), false);
            return props.schema();
        }
        Object api = doc.get("api_arguments");
        if (api instanceof List<?> names) {
            Props props = new Props();
            for (Object n : names) {
                if (n instanceof Map<?, ?> m) {
                    Map<String, Object> pm = Json.asMap(m);
                    props.add(Json.asString(pm.get("name")), pm.get("type"), pm.get("description"), pm.get("default"), false);
                } else {
                    props.add(String.valueOf(n), "string", null, null, false);
                }
            }
            return props.schema();
        }
        if (api instanceof Map<?, ?> defaults) {
            Props props = new Props();
            Json.asMap(defaults).forEach((k, v) -> props.add(k, "string", null, v, false));
            return props.schema();
        }
        return new Props().schema();
    }

    /** Maps a dataset type name to a JSON Schema type; never returns null. */
    public static String jsonType(Object raw) {
        String t = raw == null ? "" : raw.toString().toLowerCase(Locale.ROOT).replace(", optional", "")
                .replace("optional", "").strip();
        if (t.startsWith("list") || t.startsWith("array") || t.startsWith("tuple") || t.startsWith("set")
                || t.endsWith("[]")) {
            return "array";
        }
        if (t.startsWith("dict") || t.startsWith("object") || t.startsWith("map") || t.equals("json")) {
            return "object";
        }
        return switch (t) {
            case "int", "integer", "long", "int32", "int64" -> "integer";
            case "number", "float", "double", "decimal", "numeric" -> "number";
            case "bool", "boolean" -> "boolean";
            default -> "string";
        };
    }

    private static boolean isOptional(Object rawType) {
        return rawType != null && rawType.toString().toLowerCase(Locale.ROOT).contains("optional");
    }

    private static boolean isSchemaObject(Map<String, Object> p) {
        Object type = p.get("type");
        return (type instanceof String s && (s.equals("object") || s.equals("dict"))) && p.get("properties") instanceof Map;
    }

    private static Map<String, Object> fromParamMap(Map<String, Object> params) {
        Props props = new Props();
        params.forEach((name, v) -> {
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> pm = Json.asMap(m);
                Object type = pm.get("type");
                boolean required = !isOptional(type) && !Boolean.FALSE.equals(pm.get("required"));
                props.add(name, type, pm.get("description"), pm.get("default"), required);
            } else {
                String text = v == null ? "" : v.toString();
                // toolalpaca: "Required. string. Two-character country code."
                String[] parts = text.split("\\.\\s*", 3);
                boolean required = text.toLowerCase(Locale.ROOT).startsWith("required");
                String type = parts.length > 1 ? parts[1] : "string";
                String desc = parts.length > 2 ? parts[2] : text;
                props.add(name, type, desc, null, required);
            }
        });
        return props.schema();
    }

    private static Map<String, Object> fromParamList(List<Object> params) {
        Props props = new Props();
        for (Object o : params) {
            Map<String, Object> p = Json.asMap(o);
            Map<String, Object> schema = Json.asMap(p.get("schema"));
            Object type = p.containsKey("type") ? p.get("type") : schema.get("type");
            Object req = p.get("required");
            String in = Json.asString(p.get("in"));
            boolean required = req != null ? "true".equalsIgnoreCase(req.toString())
                    : "required".equals(in) || "path".equals(in);
            Object desc = p.get("description") != null ? p.get("description") : schema.get("description");
            props.add(Json.asString(p.get("name")), type, desc, schema.get("default"), required && !isOptional(type));
        }
        return props.schema();
    }

    private static Map<String, Object> fromSignature(String signature) {
        Props props = new Props();
        String inner = signature.strip().replaceAll("^\\(|\\)$", "");
        for (String part : inner.split(",(?![^\\[]*\\])")) {
            String[] nt = part.split(":", 2);
            String name = nt[0].strip();
            if (!name.isEmpty()) {
                props.add(name, nt.length > 1 ? nt[1].strip() : "string", null, null, true);
            }
        }
        return props.schema();
    }

    private static void addNamedList(Props props, Object list, boolean required) {
        for (Object o : Json.asList(list)) {
            Map<String, Object> p = Json.asMap(o);
            props.add(Json.asString(p.get("name")), p.get("type"), p.get("description"), p.get("default"), required);
        }
    }

    private static void addNameTypeMap(Props props, Object map, boolean required) {
        Json.asMap(map).forEach((key, desc) -> {
            Matcher m = NAME_TYPE.matcher(key);
            if (m.matches()) {
                props.add(m.group(1), m.group(2), desc, null, required);
            } else {
                props.add(key, "string", desc, null, required);
            }
        });
    }

    /** Normalizes a JSON-Schema-like map: type aliases, nested properties/items, keyword filter. */
    static Map<String, Object> normalize(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> liftedRequired = new ArrayList<>();
        Map<String, String> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null || !KEEP.contains(k)) {
                continue;
            }
            switch (k) {
                case "type" -> {
                    if (v instanceof String s) {
                        out.put("type", jsonType(s));
                    } else if (v instanceof List<?>) {
                        out.put("type", v);
                    }
                }
                case "properties" -> {
                    Map<String, Object> props = new LinkedHashMap<>();
                    Json.asMap(v).forEach((name, ps) -> {
                        String key = safeKey(name);
                        if (key == null || props.containsKey(key)) {
                            return;
                        }
                        renamed.put(name, key);
                        Map<String, Object> pm = Json.asMap(ps);
                        if (Boolean.TRUE.equals(pm.get("required"))) {
                            liftedRequired.add(key);
                        }
                        props.put(key, normalize(pm));
                    });
                    out.put("properties", props);
                }
                case "items" -> out.put("items", v instanceof Map<?, ?> m ? normalize(Json.asMap(m)) : Map.of());
                case "required" -> {
                    if (v instanceof List<?> l) {
                        out.put("required", l.stream().filter(String.class::isInstance).toList());
                    }
                }
                case "enum", "examples", "anyOf", "oneOf", "allOf" -> {
                    if (v instanceof List<?> l) {
                        out.put(k, k.endsWith("Of") ? l.stream().map(x -> (Object) normalize(Json.asMap(x))).toList() : l);
                    }
                }
                case "additionalProperties" -> {
                    if (v instanceof Map<?, ?> m) {
                        out.put(k, normalize(Json.asMap(m)));
                    } else if (v instanceof Boolean) {
                        out.put(k, v);
                    }
                }
                case "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength" -> {
                    if (v instanceof Number) {
                        out.put(k, v);
                    }
                }
                case "description", "title", "format", "pattern" -> out.put(k, v.toString());
                default -> out.put(k, v);
            }
        }
        if (out.get("properties") instanceof Map<?, ?> props) {
            Set<String> required = new LinkedHashSet<>();
            Json.asList(out.get("required")).forEach(r -> {
                String key = renamed.getOrDefault(String.valueOf(r), String.valueOf(r));
                if (props.containsKey(key)) {
                    required.add(key);
                }
            });
            required.addAll(liftedRequired);
            if (required.isEmpty()) {
                out.remove("required");
            } else {
                out.put("required", List.copyOf(required));
            }
            if (!out.containsKey("type")) {
                out.put("type", "object");
            }
        } else {
            out.remove("required");
        }
        if ("array".equals(out.get("type")) && !out.containsKey("items")) {
            out.put("items", Map.of());
        }
        if (out.containsKey("default") && !matchesType(out.get("default"), out.get("type"))) {
            out.remove("default");
        }
        return out;
    }

    private static Map<String, Object> objectRoot(Map<String, Object> schema) {
        schema.put("type", "object");
        schema.putIfAbsent("properties", new LinkedHashMap<>());
        return schema;
    }

    static boolean matchesType(Object value, Object type) {
        if (!(type instanceof String t)) {
            return true;
        }
        return switch (t) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> false;
        };
    }

    /**
     * A property name Anthropic accepts ({@code ^[a-zA-Z0-9_.-]{1,64}$}, also fine for OpenAI):
     * other characters become {@code _}, runs collapse, ends are trimmed; {@code null} if nothing
     * is left. Some ToolRet documents (gorilla) carry code snippets where names should be.
     */
    static String safeKey(String raw) {
        String k = raw.replaceAll("[^a-zA-Z0-9_.-]", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (k.isEmpty()) {
            return null;
        }
        return k.length() > 64 ? k.substring(0, 64) : k;
    }

    private static String firstText(Map<String, Object> doc, String... keys) {
        for (String k : keys) {
            if (doc.get(k) instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** Accumulates flat parameters into an object schema. */
    private static final class Props {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        void add(String rawName, Object rawType, Object description, Object defaultValue, boolean isRequired) {
            String name = rawName == null ? null : safeKey(rawName);
            if (name == null || properties.containsKey(name)) {
                return;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            String type = jsonType(rawType);
            p.put("type", type);
            if ("array".equals(type)) {
                p.put("items", Map.of());
            }
            if (description instanceof String d && !d.isBlank()) {
                p.put("description", d.strip());
            }
            if (defaultValue != null && !"".equals(defaultValue) && matchesType(defaultValue, type)) {
                p.put("default", defaultValue);
            }
            properties.put(name, p);
            if (isRequired) {
                required.add(name);
            }
        }

        Map<String, Object> schema() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", properties);
            if (!required.isEmpty()) {
                s.put("required", List.copyOf(required));
            }
            return s;
        }
    }
}
