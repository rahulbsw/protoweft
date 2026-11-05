package io.agentweave.conformance.mcp.client;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.auth.HttpAuthorizer;
import io.agentweave.mcp.McpClientConnector;
import io.agentweave.oauth.BrowserLauncher;
import io.agentweave.oauth.ClientRegistration;
import io.agentweave.oauth.OAuthClient;
import io.agentweave.oauth.TokenResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The suite's {@code auth/*} client scenarios required for 2025-11-25
 * ({@code src/scenarios/client/auth/*.ts}). Each scenario's mock MCP server answers 401 (or 403)
 * with a {@code WWW-Authenticate} challenge; the connector hands it to agentweave-oauth's
 * {@link OAuthClient} ({@code McpClientConnector.http(..).authorizer(..)}), which discovers the
 * protected resource and authorization server metadata, registers, runs the authorization code
 * grant with PKCE and {@code resource}, and retries with the token. The suite judges the result
 * from what its mock servers saw.
 *
 * <p>The client is configured the way an application would be, with nothing tailored to one
 * scenario: authorization code grant, the suite's fixed Client ID Metadata Document URL
 * ({@value #CIMD_URL}, used only when the server advertises
 * {@code client_id_metadata_document_supported}), else Dynamic Client Registration.
 * {@code auth/pre-registration} instead uses the {@code client_id}/{@code client_secret} the suite
 * passes in {@link ConformanceMcpClient.Run#contextJson()}.
 *
 * <p>The suite's authorization endpoint approves at once and answers with a redirect to the
 * client's {@code redirect_uri} (it has no login page). {@link #headlessUserAgent} plays the
 * browser: it GETs the authorization URL and then the redirect location, which is the
 * OAuthClient's loopback receiver on {@code 127.0.0.1}.
 */
final class AuthScenarios {

    /** The CIMD client id the suite's {@code auth/basic-cimd} authorization server expects. */
    static final String CIMD_URL = "https://conformance-test.local/client-metadata.json";

    /** The required auth scenarios for 2025-11-25 that run the authorization code flow with registration. */
    private static final List<String> AUTHORIZATION_CODE = List.of(
            "auth/metadata-default", "auth/metadata-var1", "auth/metadata-var2", "auth/metadata-var3",
            "auth/basic-cimd",
            "auth/scope-from-www-authenticate", "auth/scope-from-scopes-supported",
            "auth/scope-omitted-when-undefined", "auth/scope-step-up", "auth/scope-retry-limit",
            "auth/token-endpoint-auth-basic", "auth/token-endpoint-auth-post", "auth/token-endpoint-auth-none");

    /** Implemented auth scenarios by name, for example {@code "auth/metadata-default"}. */
    static final Map<String, ConformanceMcpClient.Scenario> SCENARIOS = new TreeMap<>();

    static {
        AUTHORIZATION_CODE.forEach(name -> SCENARIOS.put(name, AuthScenarios::authorizationCode));
        SCENARIOS.put("auth/pre-registration", AuthScenarios::preRegistration);
    }

    private AuthScenarios() {
    }

    /** The handler for {@code scenario}, or one that fails naming it as not implemented here. */
    static ConformanceMcpClient.Scenario find(String scenario) {
        ConformanceMcpClient.Scenario s = SCENARIOS.get(scenario);
        if (s != null) {
            return s;
        }
        return run -> {
            throw new UnsupportedOperationException(scenario + " is not implemented by the conformance client; "
                    + "implemented: " + SCENARIOS.keySet());
        };
    }

    static Set<String> implemented() {
        return SCENARIOS.keySet();
    }

    /** Registration by CIMD when the server supports it, else DCR. */
    static void authorizationCode(ConformanceMcpClient.Run run) {
        ClientRegistration registration = ClientRegistration.builder()
                .clientIdMetadataDocument(URI.create(CIMD_URL))
                .clientName("agentweave-conformance-client")
                .build();
        callProtectedTool(run, registration);
    }

    /** {@code auth/pre-registration}: the server has no registration endpoint; credentials come from the context. */
    static void preRegistration(ConformanceMcpClient.Run run) {
        JsonNode context = context(run);
        String clientId = text(context, "client_id");
        String clientSecret = text(context, "client_secret");
        ClientRegistration registration = ClientRegistration.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .dynamicRegistration(false)
                .build();
        callProtectedTool(run, registration);
    }

    /**
     * Connects (the 401 → discovery → registration → token → retry flow runs on the first request
     * that is challenged), lists the tools and calls {@code test-tool}, which is what every auth
     * scenario's mock server offers. Step-up scenarios challenge {@code tools/call} with 403.
     */
    private static void callProtectedTool(ConformanceMcpClient.Run run, ClientRegistration registration) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        OAuthClient oauth = OAuthClient.builder()
                .grant(OAuthClient.Grant.AUTHORIZATION_CODE)
                .registration(registration)
                .browserLauncher(headlessUserAgent(http))
                .authorizationTimeout(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        try (McpClientConnector client = CoreScenarios.connect(run, logging(oauth))) {
            CoreScenarios.callIfListed(client, "test-tool", Map.of());
        }
    }

    /**
     * The OAuthClient as the connector's authorizer, unchanged except that each challenge and the
     * client's decision (with its reason when it gives up) is written to stderr, which the suite
     * saves as the scenario's {@code stderr.txt}.
     */
    static HttpAuthorizer logging(OAuthClient oauth) {
        return new HttpAuthorizer() {
            @Override
            public Optional<String> authorization(URI resource) {
                return oauth.authorization(resource);
            }

            @Override
            public boolean onChallenge(URI resource, int status, String wwwAuthenticate) {
                System.err.println("challenge: HTTP " + status + " from " + resource + ", WWW-Authenticate: "
                        + wwwAuthenticate);
                TokenResult result = oauth.handleUnauthorized(resource, wwwAuthenticate, status);
                System.err.println("oauth: " + result);
                return result.shouldRetry();
            }
        };
    }

    /**
     * A user agent without a user: GETs the authorization request, expects the authorization
     * server's redirect, and GETs its {@code Location} (the loopback redirect URI), which delivers
     * the authorization response to the OAuthClient. Nothing in the response is interpreted here.
     */
    static BrowserLauncher headlessUserAgent(HttpClient http) {
        return authorizationRequest -> {
            try {
                HttpResponse<String> authorize = http.send(HttpRequest.newBuilder(authorizationRequest)
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                String location = authorize.headers().firstValue("Location").orElse(null);
                if (authorize.statusCode() / 100 != 3 || location == null) {
                    throw new AgentWeaveException(ErrorCategory.AUTHENTICATION, null, "authorization endpoint answered "
                            + authorize.statusCode() + " without a redirect: " + authorize.body());
                }
                URI redirect = authorizationRequest.resolve(location);
                System.err.println("authorization redirect -> " + redirect.getScheme() + "://"
                        + redirect.getRawAuthority() + redirect.getRawPath());
                HttpResponse<String> callback = http.send(HttpRequest.newBuilder(redirect)
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                System.err.println("redirect URI answered " + callback.statusCode());
            } catch (IOException e) {
                throw new AgentWeaveException(ErrorCategory.CONNECTION, null,
                        "user agent request failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentWeaveException(ErrorCategory.CONNECTION, null, "interrupted", e);
            }
        };
    }

    private static JsonNode context(ConformanceMcpClient.Run run) {
        if (run.contextJson() == null || run.contextJson().isBlank()) {
            throw new IllegalStateException(run.scenario() + " needs MCP_CONFORMANCE_CONTEXT");
        }
        return JsonMapper.builder().build().readTree(run.contextJson());
    }

    private static String text(JsonNode context, String field) {
        JsonNode value = context.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalStateException("MCP_CONFORMANCE_CONTEXT has no string " + field);
        }
        return value.asString();
    }
}
