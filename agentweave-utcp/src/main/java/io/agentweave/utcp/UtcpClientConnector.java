package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.observe.ObservationHandler.Observation;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Uses the tools of one UTCP manual through the core {@link ToolConnector} API. Implemented from
 * the UTCP 1.1 specification, with the Python reference client as the authority on behavior.
 *
 * <pre>{@code
 * UtcpClientConnector weather = UtcpClientConnector.http("weather", "https://api.example.com/utcp")
 *         .variable("weather_API_KEY", System.getenv("WEATHER_KEY"))
 *         .build();
 * registry.register("weather", weather);      // tools appear as weather.<tool>, like UTCP's namespacing
 * weather.start();
 * }</pre>
 *
 * <p>Tools are listed under their unprefixed names. Variables in call templates are namespaced by
 * the sanitized manual name: {@code ${API_KEY}} in manual {@code my_api} looks up
 * {@code my__api_API_KEY}. Thread-safe once started. See {@link #compliance()} for coverage.
 */
public final class UtcpClientConnector implements ToolConnector {

    private static final List<String> WIRE_REVISIONS = List.of("1.0", "1.1");
    private static final Pattern NON_WORD = Pattern.compile("[^\\w]", Pattern.UNICODE_CHARACTER_CLASS);

    private final String manualName;
    private final Map<String, Object> manualTemplate;
    private final Map<String, String> variables;
    private final List<Path> dotenvFiles;
    private final UnaryOperator<String> environment;
    private final CredentialProvider credentials;
    private final ObservationHandler observations;
    private final HttpProtocol http;
    private final ComplianceReport baseReport;

    private volatile Registration registration;
    private volatile ComplianceReport report;

    private UtcpClientConnector(Builder b) {
        this.manualName = b.manualName;
        this.manualTemplate = Collections.unmodifiableMap(new LinkedHashMap<>(b.template));
        this.variables = Map.copyOf(b.variables);
        this.dotenvFiles = List.copyOf(b.dotenvFiles);
        this.environment = b.environment;
        this.credentials = b.credentials;
        this.observations = b.observations;
        HttpClient client = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(b.discoveryTimeout)
                .build();
        this.http = new HttpProtocol(client, b.discoveryTimeout, b.callTimeout, b.clock);
        this.baseReport = new ComplianceReport(ProtocolId.UTCP, "none", moduleVersion(), WIRE_REVISIONS, null,
                UtcpFeatures.SUPPORTED, UtcpFeatures.UNSUPPORTED);
        this.report = baseReport;
    }

    /** A manual fetched over HTTP(S) with {@code GET}, like {@code {"call_template_type":"http","url":...}}. */
    public static Builder http(String manualName, String manualUrl) {
        return new Builder(manualName, "http").put("url", Objects.requireNonNull(manualUrl, "manualUrl"));
    }

    /**
     * A manual given inline, {@code {"call_template_type":"text","content":...}}. Per UTCP 1.1 only
     * {@code text} tools are allowed unless {@link Builder#allowedCommunicationProtocols} says otherwise.
     */
    public static Builder text(String manualName, String manualJson) {
        return new Builder(manualName, "text").put("content", Objects.requireNonNull(manualJson, "manualJson"));
    }

    /** A manual read from a JSON file; the same protocol restriction as {@link #text} applies. */
    public static Builder file(String manualName, Path manualFile) {
        return new Builder(manualName, "file").put("file_path", manualFile.toString());
    }

    /**
     * A manual described by a raw UTCP manual call template (an entry of a UTCP client's
     * {@code manual_call_templates}); {@code name} and {@code call_template_type} are required.
     */
    public static Builder fromCallTemplate(Map<String, Object> manualCallTemplate) {
        Object name = manualCallTemplate.get("name");
        Object type = manualCallTemplate.get("call_template_type");
        if (!(name instanceof String n) || !(type instanceof String t)) {
            throw new IllegalArgumentException("manual call template needs string 'name' and 'call_template_type'");
        }
        Builder b = new Builder(n, t);
        manualCallTemplate.forEach((k, v) -> {
            if (!k.equals("name")) {
                b.template.put(k, v);
            }
        });
        return b;
    }

    @Override
    public ProtocolId protocol() {
        return ProtocolId.UTCP;
    }

    /** The manual name after UTCP sanitizing (every non-word character becomes {@code _}). */
    public String manualName() {
        return manualName;
    }

    /**
     * Registers the manual: substitutes variables in the manual call template, fetches and
     * validates the manual, and filters its tools by {@code allowed_communication_protocols}.
     */
    @Override
    public synchronized void start() {
        if (registration != null) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, "connector already started");
        }
        Registration reg = observe("register_manual", manualName, () -> Registration.register(manualName,
                manualTemplate, Variables.create(variables, dotenvFiles, environment), credentials, http));
        report = reg.utcpVersion() == null ? baseReport : baseReport.withNegotiatedRevision(reg.utcpVersion());
        registration = reg;
    }

    /** Before start: what is implemented. After: plus the manual's declared {@code utcp_version}, if any. */
    @Override
    public ComplianceReport compliance() {
        return report;
    }

    @Override
    public List<ToolSpec> listTools() {
        Registration reg = requireStarted();
        List<ToolSpec> specs = new ArrayList<>();
        reg.tools().forEach((name, tool) -> specs.add(spec(name, tool)));
        return List.copyOf(specs);
    }

    /**
     * Calls one tool. HTTP status ≥ 400 is a tool failure ({@link ToolOutcome#error()}) whose text
     * is the reference client's {@code "<reason>: <detail>"}; transport, variable and policy
     * failures are {@link AgentWeaveException}s.
     */
    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        Registration reg = requireStarted();
        String name = invocation.toolName();
        String qualified = manualName + "." + name;
        return observe("call_tool", qualified, () -> {
            Manual.Tool tool = reg.tools().get(name);
            if (tool == null) {
                throw notFound(reg, name, qualified);
            }
            Map<String, Object> template = Json.object(reg.variables().substitute(tool.callTemplate(), manualName));
            String type = String.valueOf(template.get("call_template_type"));
            if (!reg.allowed().contains(type)) {
                throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, "Tool '" + qualified
                        + "' uses communication protocol '" + type + "' which is not allowed by manual '"
                        + manualName + "'. Allowed protocols: " + reg.allowed());
            }
            if (type.equals("text")) {
                return toOutcome(template.get("content"));
            }
            try {
                return toOutcome(http.call(Registration.httpTemplate(template, ErrorCategory.PROTOCOL),
                        invocation.arguments()));
            } catch (HttpProtocol.StatusFailure failure) {
                return ToolOutcome.failure(failure.getMessage());
            }
        });
    }

    /** {@link #search(String, int, List)} with the reference defaults: limit 10, no tag filter. */
    public List<ToolSpec> search(String query) {
        return search(query, 10, null);
    }

    /**
     * UTCP {@code search_tools} with the {@code tag_and_description_word_match} strategy: +3 per
     * tag found in the query, +1 per description word (longer than 2) in the query.
     *
     * @param anyOfTagsRequired when non-empty, only tools carrying one of these tags are scored
     */
    public List<ToolSpec> search(String query, int limit, List<String> anyOfTagsRequired) {
        Objects.requireNonNull(query, "query");
        Registration reg = requireStarted();
        if (limit < 0) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.UTCP, "limit must be non-negative");
        }
        return observe("search_tools", query, () -> ToolSearch
                .search(List.copyOf(reg.tools().values()), query, limit, anyOfTagsRequired).stream()
                .map(t -> spec(Registration.localName(manualName, t.name()), t))
                .toList());
    }

    /** Tools of the manual that were not registered, with the reason (protocol not allowed or not implemented). */
    public Map<String, String> skippedTools() {
        Registration reg = requireStarted();
        Map<String, String> all = new LinkedHashMap<>(reg.dropped());
        all.putAll(reg.skipped());
        return Map.copyOf(all);
    }

    /** Deregisters the manual (HTTP deregistration is a no-op on the wire). Safe to call repeatedly. */
    @Override
    public synchronized void close() {
        registration = null;
    }

    private AgentWeaveException notFound(Registration reg, String name, String qualified) {
        if (reg.skipped().containsKey(name)) {
            return new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.UTCP,
                    "Tool '" + qualified + "' was not registered: " + reg.skipped().get(name));
        }
        String dropped = reg.dropped().get(name);
        return new AgentWeaveException(ErrorCategory.TOOL_NOT_FOUND, ProtocolId.UTCP,
                "Tool not found: " + qualified + (dropped == null ? "" : " (" + dropped + ")"));
    }

    private static ToolSpec spec(String name, Manual.Tool tool) {
        Map<String, Object> metadata = tool.tags().isEmpty() ? Map.of() : Map.of(ToolSpec.META_TAGS, tool.tags());
        return new ToolSpec(name, tool.description(), Json.object(withoutNulls(tool.inputs())), metadata);
    }

    /** The reference serializes unset JSON Schema keywords as null; null there means absent. */
    private static Object withoutNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (v != null) {
                    out.put(String.valueOf(k), withoutNulls(v));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> v == null ? null : withoutNulls(v)).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
        return value;
    }

    /** JSON object → text + structured; other JSON → its text; text → text. */
    static ToolOutcome toOutcome(Object result) {
        if (result instanceof String s) {
            return ToolOutcome.text(s);
        }
        if (result instanceof Map<?, ?> map) {
            return new ToolOutcome(List.of(new Content.Text(Json.write(map))), false, map);
        }
        return ToolOutcome.text(Json.write(result));
    }

    private Registration requireStarted() {
        Registration reg = registration;
        if (reg == null) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP,
                    "connector not started; call start() first");
        }
        return reg;
    }

    private <T> T observe(String operation, String target, Supplier<T> action) {
        return ObservationHandler.observe(observations, new Observation(ProtocolId.UTCP, operation, target), action);
    }

    private static String moduleVersion() {
        Properties p = new Properties();
        try (InputStream in = UtcpClientConnector.class.getResourceAsStream("agentweave-utcp.properties")) {
            if (in == null) {
                throw new IllegalStateException("agentweave-utcp.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p.getProperty("utcp.module.version");
    }

    static String sanitize(String manualName) {
        return NON_WORD.matcher(manualName).replaceAll("_");
    }

    /** Configures a {@link UtcpClientConnector}. */
    public static final class Builder {

        private final String manualName;
        private final Map<String, Object> template = new LinkedHashMap<>();
        private final Map<String, String> variables = new LinkedHashMap<>();
        private final List<Path> dotenvFiles = new ArrayList<>();
        private UnaryOperator<String> environment = System::getenv;
        private CredentialProvider credentials = CredentialProvider.NONE;
        private ObservationHandler observations = ObservationHandler.NOOP;
        private Duration discoveryTimeout = Duration.ofSeconds(10);
        private Duration callTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;
        private Clock clock = Clock.systemUTC();

        private Builder(String manualName, String type) {
            if (manualName == null || manualName.isBlank()) {
                throw new IllegalArgumentException("manual name must not be blank");
            }
            this.manualName = sanitize(manualName);
            template.put("name", this.manualName);
            template.put("call_template_type", type);
        }

        private Builder put(String key, Object value) {
            template.put(key, value);
            return this;
        }

        private Builder httpOnly(String option) {
            if (!"http".equals(template.get("call_template_type"))) {
                throw new IllegalStateException(option + " applies to http manual call templates only");
            }
            return this;
        }

        /** Discovery method ({@code http_method}); default {@code GET}. */
        public Builder httpMethod(String method) {
            return httpOnly("httpMethod").put("http_method", method);
        }

        /** A static header on the discovery request ({@code headers}). */
        public Builder header(String name, String value) {
            httpOnly("header");
            Map<String, Object> existing = Json.object(template.get("headers"));
            Map<String, Object> headers = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return put("headers", headers);
        }

        /** The UTCP {@code auth} object of the manual call template, e.g. {@code {"auth_type":"api_key",...}}. */
        public Builder auth(Map<String, Object> auth) {
            return httpOnly("auth").put("auth", Map.copyOf(auth));
        }

        /** {@code allowed_communication_protocols}; unset or empty allows only the manual's own type. */
        public Builder allowedCommunicationProtocols(String... protocols) {
            return put("allowed_communication_protocols", List.of(protocols));
        }

        /** A configured variable; keys are full lookup keys, e.g. {@code my__api_API_KEY}. Checked first. */
        public Builder variable(String key, String value) {
            variables.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder variables(Map<String, String> values) {
            values.forEach(this::variable);
            return this;
        }

        /** A {@code dotenv} variable loader, consulted after configured variables, in order added. */
        public Builder dotenv(Path envFile) {
            dotenvFiles.add(Objects.requireNonNull(envFile, "envFile"));
            return this;
        }

        /** The last variable source; default {@link System#getenv(String)}. Use {@code k -> null} to disable. */
        public Builder environment(UnaryOperator<String> lookup) {
            this.environment = Objects.requireNonNull(lookup, "lookup");
            return this;
        }

        /**
         * Credentials for the discovery request of an http manual: {@code BearerToken} becomes
         * {@code Authorization: Bearer}, {@code NamedSecret} a header named after it. They override
         * static headers and are overridden by the template's own {@code auth}; tool calls never see them.
         */
        public Builder credentials(CredentialProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        public Builder observations(ObservationHandler handler) {
            this.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Manual discovery timeout; default 10 s, as the reference client. */
        public Builder discoveryTimeout(Duration timeout) {
            this.discoveryTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Tool call (and OAuth2 token) timeout; default 30 s, as the reference client. */
        public Builder callTimeout(Duration timeout) {
            this.callTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** A custom client; it must not follow redirects itself, since every hop is re-validated here. */
        public Builder httpClient(HttpClient client) {
            if (client.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new IllegalArgumentException("the HttpClient must use HttpClient.Redirect.NEVER");
            }
            this.httpClient = client;
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public UtcpClientConnector build() {
            return new UtcpClientConnector(this);
        }
    }
}
