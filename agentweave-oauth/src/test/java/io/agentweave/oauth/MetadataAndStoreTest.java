package io.agentweave.oauth;

import io.agentweave.core.AgentWeaveException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataAndStoreTest {

    @Test
    void prmJsonIsCanonicalAndMinimal() {
        ProtectedResourceMetadata m = ProtectedResourceMetadata.builder(URI.create("HTTPS://Api.Example.com/mcp/"))
                .authorizationServer(URI.create("https://auth.example.com")).build();
        assertThat(m.toJson()).isEqualTo("{\"resource\":\"https://api.example.com/mcp\","
                + "\"authorization_servers\":[\"https://auth.example.com\"],\"bearer_methods_supported\":[\"header\"]}");
        assertThat(m.metadataPath()).isEqualTo("/.well-known/oauth-protected-resource/mcp");
        ProtectedResourceMetadata parsed = ProtectedResourceMetadata.parse(m.toJson());
        assertThat(parsed.resource()).isEqualTo(m.resource());
        assertThat(parsed.authorizationServers()).isEqualTo(m.authorizationServers());
    }

    @Test
    void prmNeedsAnAuthorizationServer() {
        assertThatThrownBy(() -> ProtectedResourceMetadata.builder(URI.create("https://a.example/mcp")).build())
                .isInstanceOf(AgentWeaveException.class).hasMessageContaining("authorization server");
    }

    @Test
    void endpointMountsPathInsertedAndOptionalRoot() {
        ProtectedResourceMetadata m = ProtectedResourceMetadata.builder(URI.create("https://a.example/public/mcp"))
                .authorizationServer(URI.create("https://auth.example.com")).build();
        assertThat(new ProtectedResourceMetadataEndpoint(m).endpoints().get(0).urlPatterns())
                .containsExactly("/.well-known/oauth-protected-resource/public/mcp");
        assertThat(new ProtectedResourceMetadataEndpoint(m, true).endpoints().get(0).urlPatterns())
                .containsExactly("/.well-known/oauth-protected-resource/public/mcp", "/.well-known/oauth-protected-resource");
    }

    @Test
    void cimdUrlMustBeHttpsWithPath() {
        assertThatThrownBy(() -> ClientRegistration.builder().clientIdMetadataDocument(URI.create("https://c.example")))
                .isInstanceOf(AgentWeaveException.class);
        assertThatThrownBy(() -> ClientRegistration.builder().clientIdMetadataDocument(URI.create("http://c.example/c.json")))
                .isInstanceOf(AgentWeaveException.class);
    }

    @Test
    void storedTokenNeverPrintsSecrets() {
        StoredToken t = new StoredToken("access-secret", "refresh-secret", Instant.now(), Set.of("a"));
        assertThat(t.toString()).doesNotContain("access-secret").doesNotContain("refresh-secret");
        assertThat(ClientRegistration.builder().clientId("c").clientSecret("pw").build().toString()).doesNotContain("pw");
    }

    @Test
    void persistedTokenIsReusedAfterDiscoveryWithoutANewGrant() throws Exception {
        try (TestAuthorizationServer as = new TestAuthorizationServer(); TestResourceServer rs = new TestResourceServer()) {
            rs.publish("/.well-known/oauth-protected-resource/mcp", "/mcp", as);
            as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
            TokenStore shared = TokenStore.inMemory();
            OAuthClient.Builder builder = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                    .tokenStore(shared)
                    .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build());
            String token = builder.build().accessToken(rs.uri("/mcp"), Challenge.NONE);

            OAuthClient restarted = builder.build();
            assertThat(restarted.cachedToken(rs.uri("/mcp"))).isEmpty(); // nothing known before discovery
            assertThat(restarted.handleUnauthorized(rs.uri("/mcp"), "Bearer", 401).accessToken()).contains(token);
            assertThat(as.tokenRequests).hasSize(1);
            assertThat(shared.load(new TokenStore.Key(rs.uri("/mcp").toString(), as.issuer(), "m2m"))).isPresent();
        }
    }
}
