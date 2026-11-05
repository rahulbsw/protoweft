package io.agentweave.oauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PRM discovery (header, path-inserted, root) and AS metadata discovery order, through {@link OAuthClient}. */
class DiscoveryTest {

    private TestAuthorizationServer as;
    private TestResourceServer rs;

    @AfterEach
    void close() {
        if (as != null) {
            as.close();
        }
        if (rs != null) {
            rs.close();
        }
    }

    private OAuthClient m2m() {
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
        return OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
                .build();
    }

    @Test
    void usesResourceMetadataUrlFromHeaderFirst() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer().publish("/custom/prm.json", "/mcp", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"),
                "Bearer resource_metadata=\"" + rs.origin() + "/custom/prm.json\"", 401);
        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(rs.requests).containsExactly("/custom/prm.json");
    }

    @Test
    void fallsBackToPathInsertedWellKnown() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource/public/mcp", "/public/mcp", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/public/mcp"), "Bearer", 401);
        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(rs.requests).containsExactly("/.well-known/oauth-protected-resource/public/mcp");
    }

    @Test
    void fallsBackToRootWellKnown() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource", "/", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/public/mcp"), null, 401);
        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(rs.requests).containsExactly("/.well-known/oauth-protected-resource/public/mcp",
                "/.well-known/oauth-protected-resource");
    }

    @Test
    void headerUrlThatFailsFallsBackToWellKnownInOrder() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource", "/mcp", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"),
                "Bearer resource_metadata=\"" + rs.origin() + "/missing\"", 401);
        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(rs.requests).containsExactly("/missing", "/.well-known/oauth-protected-resource/mcp",
                "/.well-known/oauth-protected-resource");
    }

    @Test
    void rejectsPrmForAnotherResource() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource/mcp", "/other", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.shouldRetry()).isFalse();
        assertThat(r.failure()).get().asString().contains("is for").contains("/other");
    }

    @Test
    void rejectsPrmWithoutAuthorizationServers() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer();
        rs.documents.put("/.well-known/oauth-protected-resource/mcp",
                "{\"resource\":\"" + rs.origin() + "/mcp\",\"authorization_servers\":[]}");
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.failure()).get().asString().contains("no authorization_servers");
    }

    @Test
    void failsWhenNoPrmIsPublished() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer();
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.failure()).get().asString().contains("no protected resource metadata");
    }

    @ParameterizedTest
    @EnumSource(TestAuthorizationServer.Discovery.class)
    void pathIssuerTriesOauthThenOidcInsertedThenOidcAppended(TestAuthorizationServer.Discovery variant)
            throws Exception {
        as = new TestAuthorizationServer("/tenant1");
        as.discovery = variant;
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource/mcp", "/mcp", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        List<String> order = List.of("/.well-known/oauth-authorization-server/tenant1",
                "/.well-known/openid-configuration/tenant1", "/tenant1/.well-known/openid-configuration");
        assertThat(as.metadataRequests).containsExactlyElementsOf(order.subList(0, variant.ordinal() + 1));
    }

    @Test
    void rootIssuerTriesOauthThenOidc() throws Exception {
        as = new TestAuthorizationServer();
        as.discovery = TestAuthorizationServer.Discovery.OIDC_INSERTED; // = /.well-known/openid-configuration at root
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource/mcp", "/mcp", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.shouldRetry()).as(r.toString()).isTrue();
        assertThat(as.metadataRequests).containsExactly("/.well-known/oauth-authorization-server",
                "/.well-known/openid-configuration");
    }

    @Test
    void refusesMetadataWhoseIssuerDiffers() throws Exception {
        as = new TestAuthorizationServer();
        as.metadataIssuerOverride = "http://127.0.0.1:1/evil";
        rs = new TestResourceServer().publish("/.well-known/oauth-protected-resource/mcp", "/mcp", as);
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.failure()).get().asString().contains("has issuer");
    }

    @Test
    void refusesNonHttpsAuthorizationServer() throws Exception {
        as = new TestAuthorizationServer();
        rs = new TestResourceServer();
        rs.documents.put("/.well-known/oauth-protected-resource/mcp", ProtectedResourceMetadata
                .builder(rs.uri("/mcp")).authorizationServer(URI.create("http://auth.example.com")).build().toJson());
        TokenResult r = m2m().handleUnauthorized(rs.uri("/mcp"), null, 401);
        assertThat(r.failure()).get().asString().contains("must use https");
        assertThat(as.metadataRequests).isEmpty();
    }
}
