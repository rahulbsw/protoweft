package io.github.rahulbsw.protoweft.utcp;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Serves any {@link ToolSource} as a UTCP 1.1 manual: {@code GET /utcp} returns the manual and
 * {@code POST /utcp/tools/<tool>} calls a tool, so UTCP clients call the tools directly over HTTP.
 *
 * <pre>{@code
 * ProtoweftServer.builder()
 *         .endpoints(UtcpManualProvider.builder(tools).build())
 *         .start();
 * }</pre>
 *
 * <p>Every tool is published with an HTTP call template that sends the whole argument object as
 * the JSON body ({@code body_field} {@code "body"}), so argument types survive: a UTCP client calls
 * it with {@code {"body": {...arguments...}}}. Responses: structured content as JSON, text-only
 * results as {@code text/plain}, anything else as a JSON array of parts; failures as
 * {@code {"error": "..."}} with status 500 (tool failure), 404 (unknown tool), 400 (bad
 * arguments or body), 501, 502 or 504.
 */
public final class UtcpManualProvider implements ServerEndpointProvider {

    /** The {@code utcp_version} this provider declares. */
    public static final String UTCP_VERSION = "1.1.0";

    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final ToolSource tools;
    private final Supplier<String> baseUrl;
    private final String manualVersion;
    private final Map<String, List<String>> tags;
    private final Map<String, Object> toolAuth;

    private UtcpManualProvider(Builder b) {
        this.tools = b.tools;
        this.baseUrl = b.baseUrl;
        this.manualVersion = b.manualVersion;
        this.tags = Map.copyOf(b.tags);
        this.toolAuth = b.toolAuth;
    }

    public static Builder builder(ToolSource tools) {
        return new Builder(tools);
    }

    @Override
    public List<ServletEndpoint> endpoints() {
        return List.of(new ServletEndpoint("utcp", ProtocolId.UTCP, List.of("/utcp", "/utcp/*"),
                new ManualServlet(this), false));
    }

    /** The configured public base URL, or {@code null} to derive it from each request. */
    String configuredBaseUrl() {
        return baseUrl == null ? null : baseUrl.get();
    }

    ToolSource tools() {
        return tools;
    }

    /**
     * The manual as plain values, with tool URLs under {@code base} (no trailing slash).
     *
     * @throws IllegalStateException if {@code base} contains {@code $} (a UTCP client would treat it
     *                               as a variable) or two tool names collide once made URL-safe
     */
    public Map<String, Object> manual(String base) {
        if (base.contains("$")) {
            throw new IllegalStateException("base URL must not contain '$' (UTCP clients substitute $VARIABLES): " + base);
        }
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        List<Object> entries = new ArrayList<>();
        for (Map.Entry<String, ToolSpec> e : published().entrySet()) {
            ToolSpec spec = e.getValue();
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("call_template_type", "http");
            template.put("http_method", "POST");
            template.put("url", root + "/utcp/tools/" + e.getKey());
            template.put("content_type", "application/json");
            template.put("body_field", "body");
            if (toolAuth != null) {
                template.put("auth", toolAuth);
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", e.getKey());
            tool.put("description", spec.description());
            tool.put("inputs", wrapInputs(spec.inputSchema()));
            tool.put("outputs", Map.of());
            tool.put("tags", tags.getOrDefault(spec.name(), List.of()));
            tool.put("tool_call_template", template);
            entries.add(tool);
        }
        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("utcp_version", UTCP_VERSION);
        manual.put("manual_version", manualVersion);
        manual.put("tools", entries);
        return manual;
    }

    /** Published (URL-safe, dot-free) name → tool, for the current tool list. */
    Map<String, ToolSpec> published() {
        Map<String, ToolSpec> out = new LinkedHashMap<>();
        for (ToolSpec spec : tools.listTools()) {
            String name = UNSAFE_NAME.matcher(spec.name()).replaceAll("_");
            ToolSpec previous = out.put(name, spec);
            if (previous != null) {
                throw new IllegalStateException("tools '" + previous.name() + "' and '" + spec.name()
                        + "' both publish as '" + name + "'");
            }
        }
        return out;
    }

    /** {@code {"type":"object","properties":{"body":<schema>},"required":["body"]}}; root $defs move up so refs resolve. */
    private static Map<String, Object> wrapInputs(Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>(schema);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "object");
        wrapper.put("properties", Map.of("body", body));
        wrapper.put("required", List.of("body"));
        for (String key : List.of("$defs", "definitions")) {
            if (body.containsKey(key)) {
                wrapper.put(key, body.remove(key));
            }
        }
        return wrapper;
    }

    /** Configures a {@link UtcpManualProvider}. */
    public static final class Builder {

        private final ToolSource tools;
        private Supplier<String> baseUrl;
        private String manualVersion = "1.0.0";
        private final Map<String, List<String>> tags = new LinkedHashMap<>();
        private Map<String, Object> toolAuth;

        private Builder(ToolSource tools) {
            this.tools = Objects.requireNonNull(tools, "tools");
        }

        /** Public base URL used in tool URLs, e.g. {@code https://api.example.com}. Default: from each request. */
        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            return baseUrl(() -> baseUrl);
        }

        /** A base URL known only later (for example after binding port 0). */
        public Builder baseUrl(Supplier<String> baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** {@code manual_version}; default {@code 1.0.0}. */
        public Builder manualVersion(String version) {
            this.manualVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        /** Search tags for a tool (by its {@link ToolSource} name). */
        public Builder tags(String tool, String... toolTags) {
            tags.put(Objects.requireNonNull(tool, "tool"), List.of(toolTags));
            return this;
        }

        /**
         * Tells clients to send an API key header on every tool call: emits
         * {@code {"auth_type":"api_key","api_key":"${<variable>}","var_name":<header>,"location":"header"}}.
         * A UTCP client resolves the variable namespaced by its manual name ({@code <manual>_<variable>}, with each {@code _} in the manual name doubled).
         */
        public Builder apiKeyAuth(String headerName, String variable) {
            if (headerName == null || headerName.isBlank()) {
                throw new IllegalArgumentException("header name must not be blank");
            }
            if (variable == null || !VARIABLE_NAME.matcher(variable).matches()) {
                throw new IllegalArgumentException("variable must match " + VARIABLE_NAME + ": " + variable);
            }
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("auth_type", "api_key");
            auth.put("api_key", "${" + variable + "}");
            auth.put("var_name", headerName);
            auth.put("location", "header");
            return toolAuth(auth);
        }

        /**
         * A raw UTCP {@code auth} object for every tool call template. Every {@code $name} or
         * {@code ${name}} in its strings is a client-side variable.
         */
        public Builder toolAuth(Map<String, Object> auth) {
            this.toolAuth = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(auth));
            return this;
        }

        public UtcpManualProvider build() {
            return new UtcpManualProvider(this);
        }
    }
}
