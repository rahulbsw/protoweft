package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Interactive flow with a programmatic "browser": PKCE, state, resource, CIMD, DCR, refresh. */
class AuthorizationCodeTest {

    private static final String PRM_PATH = "/.well-known/oauth-protected-resource/mcp";

    private TestAuthorizationServer as;
    private TestResourceServer rs;
    private URI resource;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Integer> callbackStatuses = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer();
        resource = rs.uri("/mcp");
        rs.publish(PRM_PATH, "/mcp", as, "mcp:tools");
    }

    @AfterEach
    void close() {
        as.close();
        rs.close();
    }

    /** Follows the authorization server's 302 back to the loopback receiver, optionally first with a forged state. */
    private BrowserLauncher browser(boolean forgeFirst) {
        return uri -> {
            try {
                HttpResponse<Void> auth = http.send(HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                String location = auth.headers().firstValue("Location").orElseThrow(
                        () -> new IllegalStateException("authorize returned " + auth.statusCode()));
                if (forgeFirst) {
                    String forged = location.replaceAll("state=[^&]*", "state=forged");
                    callbackStatuses.add(get(forged));
                }
                callbackStatuses.add(get(location));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private int get(String uri) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private OAuthClient.Builder interactive(ClientRegistration registration) {
        return OAuthClient.builder().registration(registration).browserLauncher(browser(false));
    }

    @Test
    void dynamicRegistrationPkceStateAndResourceOnBothRequests() throws Exception {
        OAuthClient client = OAuthClient.builder().browserLauncher(browser(true)).build();

        TokenResult r = client.handleUnauthorized(resource, "Bearer resource_metadata=\"" + rs.origin() + PRM_PATH + "\"", 401);

        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(callbackStatuses).containsExactly(400, 200); // forged state discarded, real one accepted
        assertThat(as.registrations).hasSize(1);
        Map<String, Object> reg = as.registrations.get(0);
        assertThat(reg).containsEntry("token_endpoint_auth_method", "none");
        assertThat(String.valueOf(reg.get("grant_types"))).contains("authorization_code").contains("refresh_token");
        assertThat(String.valueOf(((List<?>) reg.get("redirect_uris")).get(0))).matches("http://127\\.0\\.0\\.1:\\d+/callback");

        Map<String, String> authorize = as.authorizeRequests.get(0);
        assertThat(authorize).containsEntry("response_type", "code").containsEntry("code_challenge_method", "S256")
                .containsEntry("resource", resource.toString()).containsEntry("scope", "mcp:tools")
                .containsKey("state").containsKey("code_challenge");
        Map<String, String> token = as.tokenRequests.get(0);
        assertThat(token).containsEntry("grant_type", "authorization_code")
                .containsEntry("resource", resource.toString()).containsKey("code_verifier")
                .containsEntry("redirect_uri", authorize.get("redirect_uri"));
        assertThat(as.tokenAuthMethods).containsExactly("none");
        assertThat(SignedJWT.parse(r.accessToken().get()).getJWTClaimsSet().getAudience()).containsExactly(resource.toString());
    }

    @Test
    void tamperedCodeChallengeFailsPkceAtTheTokenEndpoint() {
        BrowserLauncher tampering = uri -> browser(false).open(URI.create(
                uri.toString().replaceAll("code_challenge=[^&]*", "code_challenge=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")));
        OAuthClient client = OAuthClient.builder().browserLauncher(tampering).build();
        TokenResult r = client.handleUnauthorized(resource, null, 401);
        assertThat(r.failure()).get().asString().contains("PKCE verification failed");
    }

    @Test
    void authorizationResponseFromAnotherIssuerIsRejected() {
        BrowserLauncher mixUp = uri -> {
            try {
                HttpResponse<Void> auth = http.send(HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                get(auth.headers().firstValue("Location").orElseThrow().replaceAll("iss=[^&]*", "iss=https%3A%2F%2Fevil.example"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        OAuthClient client = OAuthClient.builder().browserLauncher(mixUp).build();
        assertThat(client.handleUnauthorized(resource, null, 401).failure()).get().asString()
                .contains("expected " + as.issuer());
        assertThat(as.tokenRequests).isEmpty();
    }

    @Test
    void refusesWithoutCodeChallengeMethodsSupported() {
        as.pkceAdvertised = false;
        OAuthClient client = OAuthClient.builder().browserLauncher(browser(false)).build();
        TokenResult r = client.handleUnauthorized(resource, null, 401);
        assertThat(r.shouldRetry()).isFalse();
        assertThat(r.failure()).get().asString().contains("code_challenge_methods_supported");
        assertThat(as.authorizeRequests).isEmpty();
        assertThat(as.registrations).isEmpty();
    }

    @Test
    void preRegisteredPublicClientUsesNoneAuth() {
        as.clients.put("pre", new TestAuthorizationServer.Client("pre", null, "none", null));
        OAuthClient client = interactive(ClientRegistration.builder().clientId("pre").build()).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.registrations).isEmpty();
        assertThat(as.tokenAuthMethods).containsExactly("none");
        assertThat(as.authorizeRequests.get(0)).containsEntry("client_id", "pre");
    }

    @Test
    void preRegisteredConfidentialClientUsesBasic() {
        as.clients.put("pre", new TestAuthorizationServer.Client("pre", "pw", "client_secret_basic", null));
        OAuthClient client = interactive(ClientRegistration.builder().clientId("pre").clientSecret("pw").build()).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.tokenAuthMethods).containsExactly("client_secret_basic");
    }

    @Test
    void clientIdMetadataDocumentUsedWhenAdvertised() {
        as.cimdSupported = true;
        URI cimd = URI.create("https://client.example.com/oauth/client.json");
        OAuthClient client = interactive(ClientRegistration.builder().clientIdMetadataDocument(cimd).build()).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.registrations).isEmpty();
        assertThat(as.authorizeRequests.get(0)).containsEntry("client_id", cimd.toString());
        assertThat(as.tokenRequests.get(0)).containsEntry("client_id", cimd.toString());
    }

    @Test
    void clientIdMetadataDocumentIgnoredWhenNotAdvertisedFallsBackToDcr() {
        URI cimd = URI.create("https://client.example.com/oauth/client.json");
        OAuthClient client = interactive(ClientRegistration.builder().clientIdMetadataDocument(cimd).build()).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.registrations).hasSize(1);
        assertThat(as.authorizeRequests.get(0).get("client_id")).startsWith("dyn-");
    }

    @Test
    void preRegistrationBeatsCimdAndDcr() {
        as.cimdSupported = true;
        as.clients.put("pre", new TestAuthorizationServer.Client("pre", null, "none", null));
        OAuthClient client = interactive(ClientRegistration.builder().clientId("pre")
                .clientIdMetadataDocument(URI.create("https://client.example.com/c.json")).build()).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.authorizeRequests.get(0)).containsEntry("client_id", "pre");
        assertThat(as.registrations).isEmpty();
    }

    @Test
    void failsWithoutAnyRegistrationOption() {
        as.registrationEnabled = false;
        OAuthClient client = interactive(ClientRegistration.dynamic()).build();
        TokenResult r = client.handleUnauthorized(resource, null, 401);
        assertThat(r.failure()).get().asString().contains("no way to register");
    }

    @Test
    void refreshesExpiredTokenWithRefreshToken() throws Exception {
        as.expiresIn = 1; // inside the client's 30 s expiry margin: treated as expired at once
        OAuthClient client = interactive(ClientRegistration.dynamic()).build();
        String first = client.accessToken(resource, Challenge.NONE);

        String refreshed = client.cachedToken(resource).orElseThrow();

        assertThat(refreshed).isNotEqualTo(first);
        assertThat(as.authorizeRequests).hasSize(1);
        assertThat(as.tokenRequests).hasSize(2);
        assertThat(as.tokenRequests.get(1)).containsEntry("grant_type", "refresh_token")
                .containsEntry("resource", resource.toString()).containsKey("refresh_token");
    }

    @Test
    void unauthorizedWithRefreshTokenRefreshesInsteadOfReauthorizing() {
        OAuthClient client = interactive(ClientRegistration.dynamic()).build();
        client.accessToken(resource, Challenge.NONE);
        TokenResult r = client.handleUnauthorized(resource, "Bearer error=\"invalid_token\"", 401);
        assertThat(r.shouldRetry()).isTrue();
        assertThat(as.authorizeRequests).hasSize(1);
        assertThat(as.tokenRequests.get(1)).containsEntry("grant_type", "refresh_token");
    }

    @Test
    void stepUpReauthorizesInteractivelyWithChallengedScope() {
        OAuthClient client = interactive(ClientRegistration.dynamic()).build();
        client.accessToken(resource, Challenge.NONE);
        TokenResult r = client.handleUnauthorized(resource,
                "Bearer error=\"insufficient_scope\", scope=\"mcp:tools mcp:admin\"", 403);
        assertThat(r.shouldRetry()).isTrue();
        assertThat(as.authorizeRequests).hasSize(2);
        assertThat(as.authorizeRequests.get(1)).containsEntry("scope", "mcp:tools mcp:admin")
                .containsEntry("resource", resource.toString());
        assertThat(as.registrations).hasSize(1); // the loopback port, and so the registration, is reused
    }

    @Test
    void httpsRedirectUsesApplicationCallbackAndChecksState() {
        URI redirect = URI.create("https://app.example.com/oauth/callback");
        as.clients.put("web", new TestAuthorizationServer.Client("web", null, "none", redirect.toString()));
        OAuthClient client = OAuthClient.builder()
                .registration(ClientRegistration.builder().clientId("web").build())
                .redirectUri(redirect, request -> {
                    HttpResponse<Void> auth = http.send(HttpRequest.newBuilder(request).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    return URI.create(auth.headers().firstValue("Location").orElseThrow());
                }).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.tokenRequests.get(0)).containsEntry("redirect_uri", redirect.toString());

        OAuthClient forging = OAuthClient.builder()
                .registration(ClientRegistration.builder().clientId("web").build())
                .redirectUri(redirect, request -> URI.create(redirect + "?code=x&state=forged")).build();
        assertThat(forging.handleUnauthorized(resource, null, 401).failure()).get().asString()
                .contains("state does not match");
    }
}
