package io.github.rahulbsw.protoweft.oauth;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrisAndChallengeTest {

    @Test
    void aBlankChallengeScopeIsAbsentSoSelectionFallsBackToTheMetadata() {
        // Unicode whitespace needs isBlank(); control characters need trim(): both checks matter.
        assertThat(Challenge.parse("Bearer scope=\"\u2003\"").scope()).isNull();
        assertThat(Challenge.parse("Bearer scope=\"\u0001\"").scope()).isNull();
        assertThat(Challenge.parse("Bearer scope=\"  \"").scope()).isNull();
        assertThat(Challenge.parse("Bearer scope=\" files:read \"").scope()).isEqualTo("files:read");
    }

    @Test
    void canonicalResourceLowercasesAndStripsFragmentTrailingSlashAndDefaultPort() {
        assertThat(OAuthClient.canonicalResource(URI.create("HTTPS://MCP.Example.com/mcp/#frag")))
                .hasToString("https://mcp.example.com/mcp");
        assertThat(OAuthClient.canonicalResource(URI.create("https://mcp.example.com/")))
                .hasToString("https://mcp.example.com");
        assertThat(OAuthClient.canonicalResource(URI.create("https://mcp.example.com:443/a")))
                .hasToString("https://mcp.example.com/a");
        assertThat(OAuthClient.canonicalResource(URI.create("https://mcp.example.com:8443")))
                .hasToString("https://mcp.example.com:8443");
        // Path case is significant and kept.
        assertThat(OAuthClient.canonicalResource(URI.create("https://h.example/Server/MCP")))
                .hasToString("https://h.example/Server/MCP");
    }

    @Test
    void canonicalResourceRejectsRelativeAndNonHttp() {
        assertThatThrownBy(() -> OAuthClient.canonicalResource(URI.create("mcp.example.com")))
                .isInstanceOf(ProtoweftException.class);
        assertThatThrownBy(() -> OAuthClient.canonicalResource(URI.create("ftp://mcp.example.com/x")))
                .isInstanceOf(ProtoweftException.class);
    }

    @Test
    void wellKnownUrisFollowRfc9728AndTheMcpAsOrder() {
        URI resource = URI.create("https://example.com/public/mcp");
        assertThat(Uris.prmWellKnown(resource))
                .hasToString("https://example.com/.well-known/oauth-protected-resource/public/mcp");
        assertThat(Uris.prmRoot(resource)).hasToString("https://example.com/.well-known/oauth-protected-resource");
        assertThat(Uris.authorizationServerMetadataUris(URI.create("https://auth.example.com/tenant1")))
                .extracting(URI::toString).containsExactly(
                        "https://auth.example.com/.well-known/oauth-authorization-server/tenant1",
                        "https://auth.example.com/.well-known/openid-configuration/tenant1",
                        "https://auth.example.com/tenant1/.well-known/openid-configuration");
        assertThat(Uris.authorizationServerMetadataUris(URI.create("https://auth.example.com")))
                .extracting(URI::toString).containsExactly(
                        "https://auth.example.com/.well-known/oauth-authorization-server",
                        "https://auth.example.com/.well-known/openid-configuration");
    }

    @Test
    void prmResourceMustBeSameOriginPathPrefix() {
        assertThat(Uris.covers(URI.create("https://a.example/mcp"), URI.create("https://A.example/mcp/"))).isTrue();
        assertThat(Uris.covers(URI.create("https://a.example"), URI.create("https://a.example/mcp"))).isTrue();
        assertThat(Uris.covers(URI.create("https://a.example/mc"), URI.create("https://a.example/mcp"))).isFalse();
        assertThat(Uris.covers(URI.create("https://b.example/mcp"), URI.create("https://a.example/mcp"))).isFalse();
    }

    @Test
    void httpsRequiredExceptLoopback() {
        assertThat(Uris.requireSecure(URI.create("http://127.0.0.1:9/token"), "t")).isNotNull();
        assertThat(Uris.requireSecure(URI.create("http://[::1]:9/token"), "t")).isNotNull();
        assertThat(Uris.requireSecure(URI.create("http://LocalHost:9/token"), "t")).isNotNull();
        assertThatThrownBy(() -> Uris.requireSecure(URI.create("http://localhost.example.com/token"), "t"))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> Uris.requireSecure(URI.create("http://evil-localhost/token"), "t"))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> Uris.requireSecure(URI.create("http://auth.example.com/token"), "t"))
                .hasMessageContaining("https");
    }

    @Test
    void parsesBearerChallengeAmongOthers() {
        Challenge c = Challenge.parse("Basic realm=\"x\", Bearer error=\"insufficient_scope\", "
                + "scope=\"files:read files:write\", resource_metadata=\"https://mcp.example.com/.well-known/"
                + "oauth-protected-resource/mcp\", error_description=\"needs \\\"write\\\"\"");
        assertThat(c.error()).isEqualTo("insufficient_scope");
        assertThat(c.insufficientScope()).isTrue();
        assertThat(c.scope()).isEqualTo("files:read files:write");
        assertThat(c.resourceMetadata()).hasToString(
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp");
        assertThat(c.errorDescription()).isEqualTo("needs \"write\"");
    }

    @Test
    void parsesUnquotedParamsAndMissingBearer() {
        Challenge c = Challenge.parse("bearer scope=read, resource_metadata=https://a.example/prm");
        assertThat(c.scope()).isEqualTo("read");
        assertThat(c.resourceMetadata()).hasToString("https://a.example/prm");
        assertThat(Challenge.parse("Basic realm=\"x\"")).isEqualTo(Challenge.NONE);
        assertThat(Challenge.parse(null)).isEqualTo(Challenge.NONE);
        assertThat(Challenge.parse("Bearer")).isEqualTo(Challenge.NONE);
    }
}
