package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Machine-to-machine flow: resource parameter, scope selection, token endpoint auth, refresh, step-up. */
class ClientCredentialsTest {

    private static final String PRM_PATH = "/.well-known/oauth-protected-resource/mcp";

    private TestAuthorizationServer as;
    private TestResourceServer rs;
    private URI resource;

    @BeforeEach
    void start() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer();
        resource = rs.uri("/mcp");
    }

    @AfterEach
    void close() {
        as.close();
        rs.close();
    }

    private OAuthClient client(String method) {
        TokenEndpointAuthMethod m = TokenEndpointAuthMethod.fromValue(method);
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", method, null));
        return OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret")
                        .tokenEndpointAuthMethod(m).build())
                .build();
    }

    private static Map<String, Object> claims(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getClaims();
    }

    @Test
    void obtainsTokenWithCanonicalResourceAndCachesIt() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        OAuthClient client = client("client_secret_basic");
        URI messy = URI.create(rs.origin().replace("http", "HTTP") + "/mcp/");
        assertThat(client.cachedToken(messy)).isEmpty();

        TokenResult r = client.handleUnauthorized(messy, "Bearer", 401);

        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(as.tokenRequests).hasSize(1);
        assertThat(as.tokenRequests.get(0)).containsEntry("grant_type", "client_credentials")
                .containsEntry("resource", resource.toString()).doesNotContainKey("scope");
        assertThat(claims(r.accessToken().orElseThrow()).get("aud")).asString().contains(resource.toString());
        assertThat(client.cachedToken(resource)).isEqualTo(r.accessToken());
        assertThat(client.accessToken(resource, Challenge.NONE)).isEqualTo(r.accessToken().get());
        assertThat(as.tokenRequests).hasSize(1);
        assertThat(r.toString()).doesNotContain(r.accessToken().get());
    }

    @Test
    void scopeFromChallengeWins() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as, "a", "b");
        client("client_secret_basic").handleUnauthorized(resource, "Bearer scope=\"x y\"", 401);
        assertThat(as.tokenRequests.get(0)).containsEntry("scope", "x y");
    }

    @Test
    void scopeFallsBackToScopesSupported() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as, "a", "b");
        client("client_secret_basic").handleUnauthorized(resource, "Bearer", 401);
        assertThat(as.tokenRequests.get(0)).containsEntry("scope", "a b");
    }

    @Test
    void scopeOmittedWhenNeitherIsKnown() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        client("client_secret_basic").handleUnauthorized(resource, "Bearer", 401);
        assertThat(as.tokenRequests.get(0)).doesNotContainKey("scope");
    }

    @Test
    void tokenEndpointAuthBasic() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        assertThat(client("client_secret_basic").handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.tokenAuthMethods).containsExactly("client_secret_basic");
        assertThat(as.tokenRequests.get(0)).doesNotContainKey("client_secret");
    }

    @Test
    void tokenEndpointAuthPost() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        assertThat(client("client_secret_post").handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.tokenAuthMethods).containsExactly("client_secret_post");
        assertThat(as.tokenRequests.get(0)).containsEntry("client_id", "m2m").containsEntry("client_secret", "s3cret");
    }

    @Test
    void defaultAuthMethodFollowsServerSupport() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        as.authMethodsSupported = java.util.List.of("client_secret_post");
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_post", null));
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build()).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.tokenAuthMethods).containsExactly("client_secret_post");
    }

    @Test
    void dynamicRegistrationForClientCredentials() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS).build();
        assertThat(client.handleUnauthorized(resource, null, 401).shouldRetry()).isTrue();
        assertThat(as.registrations).hasSize(1);
        assertThat(as.registrations.get(0)).containsEntry("token_endpoint_auth_method", "client_secret_basic")
                .containsEntry("client_name", "Protoweft");
        assertThat(as.tokenAuthMethods).containsExactly("client_secret_basic");
    }

    @Test
    void invalidClientGivesUpWithoutLeakingSecrets() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "other", "client_secret_basic", null));
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build()).build();
        TokenResult r = client.handleUnauthorized(resource, null, 401);
        assertThat(r.shouldRetry()).isFalse();
        assertThat(r.failure()).get().asString().contains("invalid_client").doesNotContain("s3cret");
    }

    @Test
    void stepUpOnInsufficientScopeUsesNewScopeAndIsBounded() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as, "read");
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
                .maxStepUpRetries(2).build();
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
        String first = client.accessToken(resource, Challenge.NONE);
        assertThat(claims(first)).containsEntry("scope", "read");

        String header = "Bearer error=\"insufficient_scope\", scope=\"read write\", resource_metadata=\""
                + rs.origin() + PRM_PATH + "\"";
        TokenResult up = client.handleUnauthorized(resource, header, 403);
        assertThat(up.shouldRetry()).isTrue();
        assertThat(claims(up.accessToken().get())).containsEntry("scope", "read write");
        assertThat(client.cachedToken(resource)).isEqualTo(up.accessToken());

        assertThat(client.handleUnauthorized(resource, header, 403).shouldRetry()).isTrue();
        TokenResult third = client.handleUnauthorized(resource, header, 403);
        assertThat(third.shouldRetry()).isFalse();
        assertThat(third.failure()).get().asString().contains("giving up after 2");
        assertThat(as.tokenRequests).hasSize(3);
    }

    @Test
    void forbiddenWithoutInsufficientScopeIsNotRetried() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        TokenResult r = client("client_secret_basic").handleUnauthorized(resource, "Bearer error=\"access_denied\"", 403);
        assertThat(r.shouldRetry()).isFalse();
        assertThat(as.tokenRequests).isEmpty();
    }

    @Test
    void repeated401sAreBounded() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
                .maxStepUpRetries(1).retryWindow(Duration.ofMinutes(5)).build();
        String a = client.handleUnauthorized(resource, "Bearer", 401).accessToken().orElseThrow();
        String b = client.handleUnauthorized(resource, "Bearer error=\"invalid_token\"", 401).accessToken().orElseThrow();
        assertThat(b).isNotEqualTo(a); // the rejected token is not handed back
        assertThat(client.handleUnauthorized(resource, "Bearer", 401).shouldRetry()).isFalse();
    }

    @Test
    void retryWindowForgetsOldAttempts() throws Exception {
        rs.publish(PRM_PATH, "/mcp", as);
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
                .maxStepUpRetries(0).retryWindow(Duration.ZERO).build();
        assertThat(client.handleUnauthorized(resource, "Bearer", 401).shouldRetry()).isTrue();
        Thread.sleep(5);
        assertThat(client.handleUnauthorized(resource, "Bearer", 401).shouldRetry()).isTrue();
    }
}
