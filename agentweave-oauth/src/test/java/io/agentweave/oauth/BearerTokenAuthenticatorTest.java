package io.agentweave.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BearerTokenAuthenticatorTest {

    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");
    private static final String PRM = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";

    private TestAuthorizationServer as;
    private ProtectedResourceMetadata metadata;
    private BearerTokenAuthenticator auth;

    @BeforeEach
    void start() throws Exception {
        as = new TestAuthorizationServer();
        metadata = ProtectedResourceMetadata.builder(RESOURCE).authorizationServer(URI.create(as.issuer()))
                .scopesSupported("mcp:tools").build();
        JwtTokenVerifier verifier = JwtTokenVerifier.builder().jwks(as.jwksJson()).issuer(as.issuer())
                .audience(RESOURCE).build();
        auth = BearerTokenAuthenticator.builder(metadata, verifier)
                .requiredScopes("mcp:tools")
                .requireScopes("POST", "/mcp/admin", "mcp:admin")
                .build();
    }

    @AfterEach
    void close() {
        as.close();
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(as.issuer()).subject("alice").audience("HTTPS://MCP.EXAMPLE.COM/mcp/")
                .claim("client_id", "app").claim("scope", "mcp:tools")
                .expirationTime(new Date(System.currentTimeMillis() + 600_000));
    }

    private static InboundRequest request(String method, String path, String token) {
        Map<String, List<String>> headers = token == null ? Map.of() : Map.of("Authorization", List.of("Bearer " + token));
        return new InboundRequest(ProtocolId.MCP, method, path, headers, "127.0.0.1");
    }

    private AuthDecision.Deny deny(AuthDecision d) {
        assertThat(d).isInstanceOf(AuthDecision.Deny.class);
        return (AuthDecision.Deny) d;
    }

    @Test
    void validTokenIsAllowedWithScopesAndClaims() throws Exception {
        AuthDecision d = auth.authenticate(request("POST", "/mcp", as.sign(claims().build())));
        assertThat(d).isInstanceOf(AuthDecision.Allow.class);
        CallerIdentity caller = ((AuthDecision.Allow) d).caller();
        assertThat(caller.name()).isEqualTo("alice");
        assertThat(caller.attributes().get("scopes")).isEqualTo(List.of("mcp:tools"));
        assertThat(((Map<?, ?>) caller.attributes().get("claims")).get("client_id")).isEqualTo("app");
        assertThat(caller.attributes().toString()).doesNotContain("Bearer");
    }

    @Test
    void clientIdIsTheNameWithoutSubject() throws Exception {
        JWTClaimsSet c = new JWTClaimsSet.Builder(claims().build()).subject(null).build();
        AuthDecision d = auth.authenticate(request("POST", "/mcp", as.sign(c)));
        assertThat(((AuthDecision.Allow) d).caller().name()).isEqualTo("app");
    }

    @Test
    void missingTokenGets401WithResourceMetadataAndScope() {
        AuthDecision.Deny d = deny(auth.authenticate(request("POST", "/mcp", null)));
        assertThat(d.status()).isEqualTo(401);
        assertThat(d.challenge()).isEqualTo("Bearer resource_metadata=\"" + PRM + "\", scope=\"mcp:tools\"");
    }

    @Test
    void expiredTokenGets401InvalidToken() throws Exception {
        String token = as.sign(claims().expirationTime(new Date(System.currentTimeMillis() - 120_000)).build());
        AuthDecision.Deny d = deny(auth.authenticate(request("POST", "/mcp", token)));
        assertThat(d.status()).isEqualTo(401);
        assertThat(d.reason()).contains("Expired");
        assertThat(d.challenge()).isEqualTo(
                "Bearer error=\"invalid_token\", resource_metadata=\"" + PRM + "\", scope=\"mcp:tools\"");
    }

    @Test
    void expiryWithinClockSkewIsAccepted() throws Exception {
        String token = as.sign(claims().expirationTime(new Date(System.currentTimeMillis() - 5_000)).build());
        assertThat(auth.authenticate(request("POST", "/mcp", token))).isInstanceOf(AuthDecision.Allow.class);
    }

    @Test
    void notYetValidTokenIsRejected() throws Exception {
        String token = as.sign(claims().notBeforeTime(new Date(System.currentTimeMillis() + 600_000)).build());
        assertThat(deny(auth.authenticate(request("POST", "/mcp", token))).status()).isEqualTo(401);
    }

    @Test
    void tokenWithoutExpiryIsRejected() throws Exception {
        String token = as.sign(new JWTClaimsSet.Builder(claims().build()).expirationTime(null).build());
        assertThat(deny(auth.authenticate(request("POST", "/mcp", token))).status()).isEqualTo(401);
    }

    @Test
    void wrongAudienceGets401() throws Exception {
        String token = as.sign(claims().audience("https://other.example.com/mcp").build());
        AuthDecision.Deny d = deny(auth.authenticate(request("POST", "/mcp", token)));
        assertThat(d.status()).isEqualTo(401);
        assertThat(d.reason()).contains("audience");
        assertThat(d.challenge()).startsWith("Bearer error=\"invalid_token\"");
    }

    @Test
    void wrongIssuerGets401() throws Exception {
        String token = as.sign(claims().issuer("https://evil.example.com").build());
        AuthDecision.Deny d = deny(auth.authenticate(request("POST", "/mcp", token)));
        assertThat(d.status()).isEqualTo(401);
        assertThat(d.reason()).contains("issuer");
    }

    @Test
    void badSignatureGets401() throws Exception {
        try (TestAuthorizationServer other = new TestAuthorizationServer()) {
            // Same kid, different key.
            String token = other.sign(claims().build());
            AuthDecision.Deny d = deny(auth.authenticate(request("POST", "/mcp", token)));
            assertThat(d.status()).isEqualTo(401);
            assertThat(d.reason()).containsIgnoringCase("signature");
        }
        assertThat(deny(auth.authenticate(request("POST", "/mcp", "not-a-jwt"))).reason()).isEqualTo("malformed token");
    }

    @Test
    void insufficientScopeGets403WithStepUpChallenge() throws Exception {
        AuthDecision.Deny d = deny(auth.authenticate(request("POST", "/mcp/admin", as.sign(claims().build()))));
        assertThat(d.status()).isEqualTo(403);
        assertThat(d.challenge()).isEqualTo("Bearer error=\"insufficient_scope\", scope=\"mcp:tools mcp:admin\", "
                + "resource_metadata=\"" + PRM + "\"");
        // GET is not covered by the POST rule.
        assertThat(auth.authenticate(request("GET", "/mcp/admin", as.sign(claims().build()))))
                .isInstanceOf(AuthDecision.Allow.class);
    }

    @Test
    void metadataPathIsPublic() {
        assertThat(auth.authenticate(request("GET", "/.well-known/oauth-protected-resource/mcp", null)))
                .isEqualTo(AuthDecision.allow(CallerIdentity.ANONYMOUS));
        assertThat(deny(auth.authenticate(request("POST", "/.well-known/oauth-protected-resource/mcp", null))).status())
                .isEqualTo(401);
    }

    @Test
    void challengeScopeFallsBackToScopesSupported() {
        BearerTokenAuthenticator open = BearerTokenAuthenticator.builder(metadata, t -> {
            throw new InvalidTokenException("unknown token");
        }).build();
        AuthDecision.Deny d = deny(open.authenticate(request("POST", "/mcp", "opaque")));
        assertThat(d.challenge()).isEqualTo(
                "Bearer error=\"invalid_token\", resource_metadata=\"" + PRM + "\", scope=\"mcp:tools\"");
        assertThat(d.reason()).isEqualTo("unknown token");
    }

    @Test
    void customVerifierMustReportOurAudience() {
        BearerTokenAuthenticator custom = BearerTokenAuthenticator.builder(metadata,
                t -> new VerifiedToken("bob", null, java.util.Set.of("https://other.example"), java.util.Set.of(), Map.of()))
                .build();
        assertThat(deny(custom.authenticate(request("POST", "/mcp", "opaque"))).reason()).contains("audience");
        BearerTokenAuthenticator ok = BearerTokenAuthenticator.builder(metadata,
                t -> new VerifiedToken("bob", null, java.util.Set.of(RESOURCE.toString()), java.util.Set.of(), Map.of()))
                .build();
        assertThat(ok.authenticate(request("POST", "/mcp", "opaque"))).isInstanceOf(AuthDecision.Allow.class);
    }

    @Test
    void es256AndRemoteJwks() throws Exception {
        ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec").build(), claims().build());
        jwt.sign(new ECDSASigner(ec));
        JwtTokenVerifier local = JwtTokenVerifier.builder().jwks(new JWKSet(ec.toPublicJWK()).toString())
                .issuer(as.issuer()).audience(RESOURCE).build();
        assertThat(local.verify(jwt.serialize()).subject()).isEqualTo("alice");

        JwtTokenVerifier remote = JwtTokenVerifier.builder().jwksUri(URI.create(as.issuer() + "/jwks"))
                .issuer(as.issuer()).audience(RESOURCE).build();
        VerifiedToken v = remote.verify(as.sign(claims().claim("scp", List.of("a", "b")).build()));
        assertThat(v.scopes()).containsExactlyInAnyOrder("mcp:tools", "a", "b");
        assertThat(v.clientId()).isEqualTo("app");
    }
}
