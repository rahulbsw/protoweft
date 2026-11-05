package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.utcp.Manual.Auth;
import io.agentweave.utcp.Manual.HttpTemplate;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code http} communication protocol: manual discovery and tool calls, after
 * {@code utcp_http/http_communication_protocol.py}.
 */
final class HttpProtocol {

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)}");
    /** Tokens are refreshed this long before {@code expires_in} runs out. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(5);

    /** A tool call answered with HTTP status ≥ 400; {@code message} is the reference format. */
    static final class StatusFailure extends RuntimeException {
        final int status;

        StatusFailure(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    record Discovery(Object data, String finalUrl) {
    }

    private record Token(String accessToken, Instant refreshAt) {
    }

    private final HttpClient client;
    private final Duration discoveryTimeout;
    private final Duration callTimeout;
    private final Clock clock;
    private final Map<List<String>, Token> tokens = new ConcurrentHashMap<>();

    HttpProtocol(HttpClient client, Duration discoveryTimeout, Duration callTimeout, Clock clock) {
        this.client = client;
        this.discoveryTimeout = discoveryTimeout;
        this.callTimeout = callTimeout;
        this.clock = clock;
    }

    /** Fetches the manual with the manual template's own method, headers and auth. */
    Discovery discover(HttpTemplate t, Map<String, String> credentialHeaders) {
        UrlSecurity.ensureSecure(t.url(), "manual discovery", ErrorCategory.CONFIGURATION);
        Map<String, String> headers = new LinkedHashMap<>(t.headers());
        headers.putAll(credentialHeaders);
        List<Map.Entry<String, String>> query = new ArrayList<>();
        Set<String> secretHeaders = new HashSet<>();
        credentialHeaders.keySet().forEach(h -> secretHeaders.add(h.toLowerCase(Locale.ROOT)));
        applyAuth(t.auth(), headers, query, secretHeaders);
        HttpExchange.Response r = HttpExchange.send(client,
                new HttpExchange.Request(t.method(), t.url(), query, headers, null, secretHeaders),
                discoveryTimeout, "manual discovery");
        if (r.status() >= 400) {
            ErrorCategory category = r.status() == 401 || r.status() == 403 ? ErrorCategory.AUTHENTICATION
                    : ErrorCategory.PROTOCOL;
            throw new AgentWeaveException(category, ProtocolId.UTCP, String.valueOf(r.status()),
                    "Error connecting to HTTP provider '" + t.name() + "': " + r.status() + ", "
                            + HttpExchange.errorMessage(r.status(), r.body()), null);
        }
        String lowerUrl = t.url().toLowerCase(Locale.ROOT);
        if (r.contentType().toLowerCase(Locale.ROOT).contains("yaml") || lowerUrl.endsWith(".yaml")
                || lowerUrl.endsWith(".yml")) {
            throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.UTCP,
                    "YAML manuals are not supported by this connector (" + t.url() + ")");
        }
        try {
            return new Discovery(Json.parse(r.body()), r.finalUrl());
        } catch (RuntimeException e) {
            throw new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.UTCP,
                    "Error parsing spec from HTTP provider '" + t.name() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Calls one tool. Returns parsed JSON for an {@code application/json} response, else text.
     *
     * @throws StatusFailure for HTTP status ≥ 400
     */
    Object call(HttpTemplate t, Map<String, Object> args) {
        Map<String, String> headers = new LinkedHashMap<>(t.headers());
        Map<String, Object> remaining = new LinkedHashMap<>(args);
        if (t.headerFields() != null) {
            for (String field : t.headerFields()) {
                if (remaining.containsKey(field)) {
                    headers.put(field, Json.text(remaining.remove(field)));
                }
            }
        }
        Object body = null;
        if (t.bodyField() != null && remaining.containsKey(t.bodyField())) {
            body = remaining.remove(t.bodyField());
        }
        String url = pathParams(t.url(), remaining);
        UrlSecurity.ensureSecure(url, "tool invocation", ErrorCategory.PROTOCOL);
        UrlSecurity.ensureLoopbackWasDeclared(t.url(), url);

        List<Map.Entry<String, String>> query = new ArrayList<>();
        remaining.forEach((k, v) -> addQuery(query, k, v));
        Set<String> secretHeaders = new HashSet<>();
        applyAuth(t.auth(), headers, query, secretHeaders);

        byte[] bytes = null;
        if (body != null) {
            if (header(headers, "Content-Type") == null) {
                headers.put("Content-Type", t.contentType());
            }
            String contentType = header(headers, "Content-Type");
            bytes = contentType.contains("application/json") ? Json.writeBytes(body) : rawBody(body);
        }
        HttpExchange.Response r = HttpExchange.send(client,
                new HttpExchange.Request(t.method(), url, query, headers, bytes, secretHeaders),
                callTimeout, "tool invocation");
        if (r.status() >= 400) {
            throw new StatusFailure(r.status(), HttpExchange.errorMessage(r.status(), r.body()));
        }
        if (r.contentType().toLowerCase(Locale.ROOT).contains("application/json")) {
            try {
                return Json.parse(r.body());
            } catch (RuntimeException e) {
                return r.text();
            }
        }
        return r.text();
    }

    /** {@code {name}} placeholders → percent-encoded argument values, removed from {@code args}. */
    static String pathParams(String template, Map<String, Object> args) {
        Matcher m = PATH_PARAM.matcher(template);
        String url = template;
        Set<String> done = new HashSet<>();
        while (m.find()) {
            String name = m.group(1);
            if (done.contains(name)) {
                continue;
            }
            if (!args.containsKey(name)) {
                throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.UTCP,
                        "Missing required path parameter: " + name);
            }
            url = url.replace("{" + name + "}", percentEncode(Json.text(args.remove(name))));
            done.add(name);
        }
        return url;
    }

    /** Python's {@code urllib.parse.quote(value, safe="")}: everything but unreserved characters. */
    static String percentEncode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    private static void addQuery(List<Map.Entry<String, String>> query, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(v -> addQuery(query, key, v));
            return;
        }
        query.add(new SimpleEntry<>(key, Json.text(value)));
    }

    private static byte[] rawBody(Object body) {
        if (body instanceof Map<?, ?> form) {
            List<Map.Entry<String, String>> fields = new ArrayList<>();
            form.forEach((k, v) -> addQuery(fields, String.valueOf(k), v));
            return HttpExchange.withQuery("", fields).substring(1).getBytes(StandardCharsets.UTF_8);
        }
        return Json.text(body).getBytes(StandardCharsets.UTF_8);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private void applyAuth(Auth auth, Map<String, String> headers, List<Map.Entry<String, String>> query,
                           Set<String> secretHeaders) {
        if (auth instanceof Auth.ApiKey key) {
            if (key.apiKey().isEmpty()) {
                throw new AgentWeaveException(ErrorCategory.AUTHENTICATION, ProtocolId.UTCP,
                        "API key for ApiKeyAuth not found.");
            }
            switch (key.location()) {
                case "header" -> {
                    headers.put(key.varName(), key.apiKey());
                    secretHeaders.add(key.varName().toLowerCase(Locale.ROOT));
                }
                case "query" -> query.add(new SimpleEntry<>(key.varName(), key.apiKey()));
                default -> {
                    String cookie = key.varName() + "=" + key.apiKey();
                    String existing = header(headers, "Cookie");
                    headers.keySet().removeIf(h -> h.equalsIgnoreCase("Cookie"));
                    headers.put("Cookie", existing == null ? cookie : existing + "; " + cookie);
                }
            }
        } else if (auth instanceof Auth.Basic basic) {
            headers.put("Authorization", basicHeader(basic.username(), basic.password()));
        } else if (auth instanceof Auth.OAuth2 oauth) {
            headers.put("Authorization", "Bearer " + token(oauth));
            secretHeaders.add("authorization");
        }
    }

    private static String basicHeader(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Client-credentials token, cached per full credential set until {@code expires_in} runs out. */
    private String token(Auth.OAuth2 a) {
        List<String> key = List.of(a.tokenUrl(), a.clientId(), a.clientSecret(), a.scope() == null ? "" : a.scope());
        Token cached = tokens.get(key);
        if (cached != null && (cached.refreshAt() == null || clock.instant().isBefore(cached.refreshAt()))) {
            return cached.accessToken();
        }
        UrlSecurity.ensureSecure(a.tokenUrl(), "OAuth2 token URL", ErrorCategory.AUTHENTICATION);
        List<Map.Entry<String, String>> inBody = new ArrayList<>(List.of(
                new SimpleEntry<>("grant_type", "client_credentials"),
                new SimpleEntry<>("client_id", a.clientId()),
                new SimpleEntry<>("client_secret", a.clientSecret())));
        List<Map.Entry<String, String>> withBasic = new ArrayList<>(List.of(
                new SimpleEntry<>("grant_type", "client_credentials")));
        if (a.scope() != null) {
            inBody.add(new SimpleEntry<>("scope", a.scope()));
            withBasic.add(new SimpleEntry<>("scope", a.scope()));
        }
        String firstFailure;
        try {
            return fetchToken(a, key, inBody, null);
        } catch (TokenFailure e) {
            firstFailure = e.getMessage();
        }
        try {
            return fetchToken(a, key, withBasic, basicHeader(a.clientId(), a.clientSecret()));
        } catch (TokenFailure e) {
            throw new AgentWeaveException(ErrorCategory.AUTHENTICATION, ProtocolId.UTCP,
                    "OAuth2 token fetch from '" + a.tokenUrl() + "' failed with credentials in the body ("
                            + firstFailure + ") and with a Basic Auth header (" + e.getMessage() + ")");
        }
    }

    private static final class TokenFailure extends RuntimeException {
        TokenFailure(String message) {
            super(message, null, false, false);
        }
    }

    private String fetchToken(Auth.OAuth2 a, List<String> key, List<Map.Entry<String, String>> form, String basic) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        if (basic != null) {
            headers.put("Authorization", basic);
        }
        byte[] body = HttpExchange.withQuery("", form).substring(1).getBytes(StandardCharsets.UTF_8);
        HttpExchange.Response r;
        try {
            r = HttpExchange.send(client, new HttpExchange.Request("POST", a.tokenUrl(), List.of(), headers, body,
                    Set.of()), callTimeout, "OAuth2 token fetch");
        } catch (AgentWeaveException e) {
            if (e.category() == ErrorCategory.CONNECTION || e.category() == ErrorCategory.TIMEOUT) {
                throw new TokenFailure(e.getMessage());
            }
            throw e;
        }
        if (r.status() >= 400) {
            throw new TokenFailure(r.status() + " " + HttpExchange.errorMessage(r.status(), r.body()));
        }
        Map<String, Object> json;
        try {
            json = Json.object(Json.parse(r.body()));
        } catch (RuntimeException e) {
            throw new TokenFailure("token response is not JSON");
        }
        if (json == null || !(json.get("access_token") instanceof String accessToken) || accessToken.isEmpty()) {
            throw new TokenFailure("token response has no access_token");
        }
        Instant refreshAt = json.get("expires_in") instanceof Number n
                ? clock.instant().plusSeconds(n.longValue()).minus(EXPIRY_MARGIN) : null;
        tokens.put(key, new Token(accessToken, refreshAt));
        return accessToken;
    }
}
