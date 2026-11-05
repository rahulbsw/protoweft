package io.agentweave.oauth;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.agentweave.core.ProtocolId;
import io.agentweave.server.InboundAuthFilter;
import io.agentweave.server.ServletEndpoint;
import io.agentweave.server.jetty.AgentWeaveServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PRM endpoint and BearerTokenAuthenticator hosted in Jetty, exercised by OAuthClient like a connector would. */
class ProtectedResourceEndToEndTest {

    private TestAuthorizationServer as;
    private AgentWeaveServer server;
    private URI resource;
    private ProtectedResourceMetadata metadata;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        as = new TestAuthorizationServer();
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        resource = URI.create("http://127.0.0.1:" + port + "/mcp");
        metadata = ProtectedResourceMetadata.builder(resource).authorizationServer(URI.create(as.issuer()))
                .scopesSupported("mcp:tools").resourceDocumentation(URI.create("https://docs.example.com/mcp"))
                .build();
        BearerTokenAuthenticator auth = BearerTokenAuthenticator.builder(metadata, JwtTokenVerifier.builder()
                        .jwksUri(URI.create(as.issuer() + "/jwks")).issuer(as.issuer()).audience(resource).build())
                .requiredScopes("mcp:tools")
                .requireScopes("POST", "/mcp/admin", "mcp:admin")
                .build();
        server = AgentWeaveServer.builder().host("127.0.0.1").port(port)
                .endpoints(new ProtectedResourceMetadataEndpoint(metadata, true))
                .endpoint(new ServletEndpoint("mcp", ProtocolId.MCP, List.of("/mcp", "/mcp/*"), new WhoAmI(), false))
                .authenticator(auth)
                .start();
    }

    @AfterEach
    void close() {
        server.close();
        as.close();
    }

    private static final class WhoAmI extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain");
            resp.getWriter().write(InboundAuthFilter.caller(req).name()
                    + " " + InboundAuthFilter.caller(req).attributes().get("scopes"));
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.baseUrl().replace("localhost", "127.0.0.1") + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** What a transport does: send with the cached token, let the client handle 401/403, retry. */
    private HttpResponse<String> call(OAuthClient client, String method, String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.port() + path);
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody());
            client.cachedToken(resource).ifPresent(t -> b.header("Authorization", "Bearer " + t));
            HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if ((response.statusCode() != 401 && response.statusCode() != 403) || attempt == 5) {
                return response;
            }
            TokenResult r = client.handleUnauthorized(resource,
                    response.headers().firstValue("WWW-Authenticate").orElse(null), response.statusCode());
            if (!r.shouldRetry()) {
                return response;
            }
        }
    }

    @Test
    void servesMetadataAtPathInsertedAndRootWellKnown() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource")) {
            HttpResponse<String> r = get(path, null);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type")).get().asString().startsWith("application/json");
            Map<String, Object> json = JSONObjectUtils.parse(r.body());
            assertThat(json).containsEntry("resource", resource.toString())
                    .containsEntry("authorization_servers", List.of(as.issuer()))
                    .containsEntry("scopes_supported", List.of("mcp:tools"))
                    .containsEntry("bearer_methods_supported", List.of("header"))
                    .containsEntry("resource_documentation", "https://docs.example.com/mcp");
        }
        assertThat(metadata.metadataUri()).hasToString(
                "http://127.0.0.1:" + server.port() + "/.well-known/oauth-protected-resource/mcp");
    }

    @Test
    void unauthenticatedRequestGetsChallenge() throws Exception {
        HttpResponse<String> r = get("/mcp", null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Bearer resource_metadata=\""
                + metadata.metadataUri() + "\", scope=\"mcp:tools\"");
    }

    @Test
    void clientDiscoversAuthorizesAndStepsUpAgainstTheRealFilter() throws Exception {
        OAuthClient client = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build()).build();

        HttpResponse<String> first = call(client, "POST", "/mcp");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("user-1 [mcp:tools]");

        HttpResponse<String> admin = call(client, "POST", "/mcp/admin");
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body()).isEqualTo("user-1 [mcp:admin, mcp:tools]");
        assertThat(as.tokenRequests).extracting(m -> m.get("scope")).containsExactly("mcp:tools", "mcp:tools mcp:admin");
        assertThat(as.tokenRequests).allSatisfy(m -> assertThat(m).containsEntry("resource", resource.toString()));
    }

    @Test
    void tokenForAnotherResourceIsRejected() throws Exception {
        String foreign = as.sign(new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(as.issuer()).subject("x")
                .audience("http://127.0.0.1:1/other").claim("scope", "mcp:tools")
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000)).build());
        HttpResponse<String> r = get("/mcp", foreign);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).get().asString().startsWith("Bearer error=\"invalid_token\"");
        assertThat(r.body()).doesNotContain(foreign);
    }
}
