package io.agentweave.utcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed UTCP manual. Mirrors {@code utcp/data/utcp_manual.py} and {@code utcp/data/tool.py};
 * call templates stay raw maps because variables are substituted on the map form at every call.
 * Parse failures are {@link IllegalArgumentException}s; callers choose the error category.
 */
record Manual(String utcpVersion, String manualVersion, List<Tool> tools) {

    /** One tool as declared by the manual. */
    record Tool(String name, String description, Map<String, Object> inputs, Map<String, Object> outputs,
                List<String> tags, Integer averageResponseSize, Map<String, Object> callTemplate) {

        String callTemplateType() {
            return (String) callTemplate.get("call_template_type");
        }
    }

    static Manual parse(Object data) {
        Map<String, Object> m = Json.object(data);
        if (m == null) {
            throw new IllegalArgumentException("Invalid UtcpManual: expected a JSON object");
        }
        String utcpVersion = optionalString(m, "utcp_version", null, "UtcpManual");
        String manualVersion = optionalString(m, "manual_version", "1.0.0", "UtcpManual");
        List<Object> rawTools = Json.array(m.get("tools"));
        if (rawTools == null) {
            throw new IllegalArgumentException("Invalid UtcpManual: 'tools' must be a list");
        }
        List<Tool> tools = new ArrayList<>(rawTools.size());
        for (Object raw : rawTools) {
            tools.add(parseTool(raw));
        }
        return new Manual(utcpVersion, manualVersion, List.copyOf(tools));
    }

    private static Tool parseTool(Object raw) {
        Map<String, Object> t = Json.object(raw);
        if (t == null) {
            throw new IllegalArgumentException("Invalid Tool: expected a JSON object");
        }
        if (!(t.get("name") instanceof String name)) {
            throw new IllegalArgumentException("Invalid Tool: 'name' is required and must be a string");
        }
        String description = optionalString(t, "description", "", "Tool " + name);
        Map<String, Object> inputs = optionalObject(t, "inputs", name);
        Map<String, Object> outputs = optionalObject(t, "outputs", name);
        List<String> tags = stringList(t.get("tags"), "Tool " + name + ": 'tags'");
        Object size = t.get("average_response_size");
        if (size != null && !(size instanceof Integer || size instanceof Long)) {
            throw new IllegalArgumentException("Invalid Tool " + name + ": 'average_response_size' must be an integer");
        }
        Map<String, Object> template = Json.object(t.get("tool_call_template"));
        if (template == null) {
            throw new IllegalArgumentException("Invalid Tool " + name + ": 'tool_call_template' is required");
        }
        if (!(template.get("call_template_type") instanceof String)) {
            throw new IllegalArgumentException("Invalid Tool " + name + ": 'call_template_type' is required");
        }
        return new Tool(name, description, inputs, outputs, tags == null ? List.of() : tags,
                size == null ? null : ((Number) size).intValue(), template);
    }

    /** The HTTP call template ({@code utcp_http/http_call_template.py}). */
    record HttpTemplate(String name, String method, String url, String contentType, Map<String, String> headers,
                        String bodyField, List<String> headerFields, Auth auth) {

        private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

        static HttpTemplate parse(Map<String, Object> m) {
            String where = "HttpCallTemplate";
            String method = optionalString(m, "http_method", "GET", where);
            if (!METHODS.contains(method)) {
                throw new IllegalArgumentException("Invalid HttpCallTemplate: http_method must be one of " + METHODS);
            }
            if (!(m.get("url") instanceof String url)) {
                throw new IllegalArgumentException("Invalid HttpCallTemplate: 'url' is required");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            Map<String, Object> rawHeaders = m.get("headers") == null ? Map.of() : Json.object(m.get("headers"));
            if (rawHeaders == null) {
                throw new IllegalArgumentException("Invalid HttpCallTemplate: 'headers' must be an object");
            }
            rawHeaders.forEach((k, v) -> {
                if (!(v instanceof String s)) {
                    throw new IllegalArgumentException("Invalid HttpCallTemplate: header '" + k + "' must be a string");
                }
                headers.put(k, s);
            });
            String bodyField = m.containsKey("body_field") ? optionalString(m, "body_field", null, where) : "body";
            return new HttpTemplate(optionalString(m, "name", "", where), method, url,
                    optionalString(m, "content_type", "application/json", where), headers, bodyField,
                    stringList(m.get("header_fields"), "HttpCallTemplate 'header_fields'"), Auth.parse(m.get("auth")));
        }
    }

    /** Authentication objects ({@code utcp/data/auth_implementations}). */
    sealed interface Auth {

        record ApiKey(String apiKey, String varName, String location) implements Auth {
        }

        record Basic(String username, String password) implements Auth {
        }

        record OAuth2(String tokenUrl, String clientId, String clientSecret, String scope) implements Auth {
        }

        static Auth parse(Object raw) {
            if (raw == null) {
                return null;
            }
            Map<String, Object> a = Json.object(raw);
            if (a == null || !(a.get("auth_type") instanceof String type)) {
                throw new IllegalArgumentException("Invalid Auth: 'auth_type' is required");
            }
            switch (type) {
                case "api_key" -> {
                    String location = optionalString(a, "location", "header", "ApiKeyAuth");
                    if (!Set.of("header", "query", "cookie").contains(location)) {
                        throw new IllegalArgumentException("Invalid ApiKeyAuth: location must be header, query or cookie");
                    }
                    return new ApiKey(required(a, "api_key", "ApiKeyAuth"),
                            optionalString(a, "var_name", "X-Api-Key", "ApiKeyAuth"), location);
                }
                case "basic" -> {
                    return new Basic(required(a, "username", "BasicAuth"), required(a, "password", "BasicAuth"));
                }
                case "oauth2" -> {
                    return new OAuth2(required(a, "token_url", "OAuth2Auth"), required(a, "client_id", "OAuth2Auth"),
                            required(a, "client_secret", "OAuth2Auth"), optionalString(a, "scope", null, "OAuth2Auth"));
                }
                default -> throw new IllegalArgumentException("Invalid auth type: " + type);
            }
        }
    }

    static String required(Map<String, Object> m, String key, String where) {
        if (!(m.get(key) instanceof String s)) {
            throw new IllegalArgumentException("Invalid " + where + ": '" + key + "' is required and must be a string");
        }
        return s;
    }

    static String optionalString(Map<String, Object> m, String key, String fallback, String where) {
        Object v = m.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Invalid " + where + ": '" + key + "' must be a string");
        }
        return s;
    }

    private static Map<String, Object> optionalObject(Map<String, Object> m, String key, String tool) {
        Object v = m.get(key);
        if (v == null) {
            return Map.of();
        }
        Map<String, Object> o = Json.object(v);
        if (o == null) {
            throw new IllegalArgumentException("Invalid Tool " + tool + ": '" + key + "' must be a JSON Schema object");
        }
        return o;
    }

    static List<String> stringList(Object v, String what) {
        if (v == null) {
            return null;
        }
        List<Object> list = Json.array(v);
        if (list == null || !list.stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException("Invalid " + what + ": must be a list of strings");
        }
        return list.stream().map(String.class::cast).toList();
    }
}
