package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small OAuth 2.1 authorization server on loopback for tests: metadata (selectable well-known
 * variant), JWKS, authorize (302 back with code), token (client credentials, code with PKCE
 * verification, refresh), and dynamic registration. Every request is recorded.
 */
final class TestAuthorizationServer implements AutoCloseable {

    enum Discovery { OAUTH, OIDC_INSERTED, OIDC_APPENDED }

    record Client(String id, String secret, String method, String redirectUri) {
    }

    final HttpServer server;
    final RSAKey key;
    final String issuerPath;
    volatile Discovery discovery = Discovery.OAUTH;
    volatile boolean pkceAdvertised = true;
    volatile boolean cimdSupported;
    volatile boolean registrationEnabled = true;
    volatile List<String> authMethodsSupported = List.of("client_secret_basic", "client_secret_post", "none");
    volatile long expiresIn = 3600;
    volatile boolean issueRefreshTokens = true;
    volatile String metadataIssuerOverride;

    final Map<String, Client> clients = new ConcurrentHashMap<>();
    final List<String> metadataRequests = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> authorizeRequests = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    final List<String> tokenAuthMethods = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> refreshTokens = new ConcurrentHashMap<>();

    TestAuthorizationServer(String issuerPath) throws Exception {
        this.issuerPath = issuerPath;
        this.key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::route);
        server.start();
    }

    TestAuthorizationServer() throws Exception {
        this("");
    }

    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String issuer() {
        return origin() + issuerPath;
    }

    String jwksJson() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    /** Signs an access token with this server's key. */
    String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(new JOSEObjectType("at+jwt")).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private void route(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.contains("/.well-known/")) {
                metadataRequests.add(path);
                if (path.equals(metadataPath())) {
                    json(ex, 200, metadata());
                } else {
                    json(ex, 404, Map.of("error", "not_found"));
                }
            } else if (path.equals(issuerPath + "/jwks")) {
                send(ex, 200, "application/json", jwksJson());
            } else if (path.equals(issuerPath + "/authorize")) {
                authorize(ex);
            } else if (path.equals(issuerPath + "/token")) {
                token(ex);
            } else if (path.equals(issuerPath + "/register")) {
                register(ex);
            } else {
                json(ex, 404, Map.of("error", "not_found"));
            }
        } catch (Exception e) {
            json(ex, 500, Map.of("error", "server_error", "error_description", String.valueOf(e)));
        }
    }

    String metadataPath() {
        return switch (discovery) {
            case OAUTH -> "/.well-known/oauth-authorization-server" + issuerPath;
            case OIDC_INSERTED -> "/.well-known/openid-configuration" + issuerPath;
            case OIDC_APPENDED -> issuerPath + "/.well-known/openid-configuration";
        };
    }

    private Map<String, Object> metadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", metadataIssuerOverride != null ? metadataIssuerOverride : issuer());
        m.put("authorization_endpoint", issuer() + "/authorize");
        m.put("token_endpoint", issuer() + "/token");
        m.put("jwks_uri", issuer() + "/jwks");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token", "client_credentials"));
        m.put("token_endpoint_auth_methods_supported", authMethodsSupported);
        if (registrationEnabled) {
            m.put("registration_endpoint", issuer() + "/register");
        }
        if (pkceAdvertised) {
            m.put("code_challenge_methods_supported", List.of("S256"));
        }
        if (cimdSupported) {
            m.put("client_id_metadata_document_supported", true);
        }
        if (discovery != Discovery.OAUTH) {
            m.put("subject_types_supported", List.of("public"));
            m.put("id_token_signing_alg_values_supported", List.of("RS256"));
        }
        return m;
    }

    private void authorize(HttpExchange ex) throws IOException {
        Map<String, String> p = form(ex.getRequestURI().getRawQuery());
        authorizeRequests.add(p);
        String clientId = p.get("client_id");
        boolean known = clients.containsKey(clientId) || (cimdSupported && clientId != null && clientId.startsWith("https://"));
        Client c = clients.get(clientId);
        if (!known || (c != null && c.redirectUri() != null && !c.redirectUri().equals(p.get("redirect_uri")))
                || !"S256".equals(p.get("code_challenge_method")) || p.get("code_challenge") == null) {
            json(ex, 400, Map.of("error", "invalid_request"));
            return;
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, p);
        String location = p.get("redirect_uri") + "?code=" + code + "&state=" + enc(p.get("state")) + "&iss=" + enc(issuer());
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange ex) throws Exception {
        Map<String, String> p = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(p);
        String clientId;
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String method;
        String secret;
        if (header != null && header.startsWith("Basic ")) {
            String[] idSecret = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8).split(":", 2);
            clientId = URLDecoder.decode(idSecret[0], StandardCharsets.UTF_8);
            secret = URLDecoder.decode(idSecret[1], StandardCharsets.UTF_8);
            method = "client_secret_basic";
        } else if (p.containsKey("client_secret")) {
            clientId = p.get("client_id");
            secret = p.get("client_secret");
            method = "client_secret_post";
        } else {
            clientId = p.get("client_id");
            secret = null;
            method = "none";
        }
        tokenAuthMethods.add(method);
        Client c = clients.get(clientId);
        boolean cimd = cimdSupported && clientId != null && clientId.startsWith("https://") && method.equals("none");
        if (!cimd && (c == null || !c.method().equals(method) || (c.secret() != null && !c.secret().equals(secret)))) {
            json(ex, 401, Map.of("error", "invalid_client"));
            return;
        }
        String scope = p.get("scope");
        String resource = p.get("resource");
        switch (p.getOrDefault("grant_type", "")) {
            case "client_credentials" -> { }
            case "authorization_code" -> {
                Map<String, String> auth = codes.remove(p.get("code"));
                if (auth == null || !auth.get("redirect_uri").equals(p.get("redirect_uri"))
                        || !auth.get("client_id").equals(clientId)) {
                    json(ex, 400, Map.of("error", "invalid_grant"));
                    return;
                }
                String verifier = p.get("code_verifier");
                String expected = verifier == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                if (expected == null || !expected.equals(auth.get("code_challenge"))) {
                    json(ex, 400, Map.of("error", "invalid_grant", "error_description", "PKCE verification failed"));
                    return;
                }
                scope = auth.get("scope");
            }
            case "refresh_token" -> {
                Map<String, String> grant = refreshTokens.remove(p.getOrDefault("refresh_token", ""));
                if (grant == null) {
                    json(ex, 400, Map.of("error", "invalid_grant"));
                    return;
                }
                scope = grant.get("scope");
            }
            default -> {
                json(ex, 400, Map.of("error", "unsupported_grant_type"));
                return;
            }
        }
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer()).subject("user-1")
                .claim("client_id", clientId).issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + Math.max(expiresIn, 1) * 1000))
                .jwtID(UUID.randomUUID().toString());
        if (resource != null) {
            claims.audience(resource);
        }
        if (scope != null) {
            claims.claim("scope", scope);
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("access_token", sign(claims.build()));
        reply.put("token_type", "Bearer");
        reply.put("expires_in", expiresIn);
        if (scope != null) {
            reply.put("scope", scope);
        }
        if (issueRefreshTokens && !"client_credentials".equals(p.get("grant_type"))) {
            String refresh = UUID.randomUUID().toString();
            Map<String, String> grant = new LinkedHashMap<>();
            if (scope != null) {
                grant.put("scope", scope);
            }
            refreshTokens.put(refresh, grant);
            reply.put("refresh_token", refresh);
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        json(ex, 200, reply);
    }

    private void register(HttpExchange ex) throws Exception {
        Map<String, Object> body = JSONObjectUtils.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        registrations.add(body);
        String method = (String) body.getOrDefault("token_endpoint_auth_method", "client_secret_basic");
        String id = "dyn-" + UUID.randomUUID();
        String secret = method.equals("none") ? null : "secret-" + UUID.randomUUID();
        List<?> redirects = (List<?>) body.get("redirect_uris");
        clients.put(id, new Client(id, secret, method, redirects == null ? null : String.valueOf(redirects.get(0))));
        Map<String, Object> reply = new LinkedHashMap<>(body);
        reply.put("client_id", id);
        if (secret != null) {
            reply.put("client_secret", secret);
        }
        json(ex, 201, reply);
    }

    static Map<String, String> form(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return Collections.unmodifiableMap(out);
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange ex, int status, Map<String, ?> body) throws IOException {
        send(ex, status, "application/json", JSONObjectUtils.toJSONString(body));
    }

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    List<String> paramValues(List<Map<String, String>> requests, String name) {
        List<String> out = new ArrayList<>();
        requests.forEach(r -> out.add(r.get(name)));
        return out;
    }

    Set<String> clientIds() {
        return clients.keySet();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
