package io.agentweave.oauth;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.mcp.McpClientConnector;
import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.jetty.AgentWeaveServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP authorization end to end: an AgentWeave MCP server protected as an OAuth resource server,
 * and the AgentWeave MCP client discovering the authorization server from the 401 challenge and
 * obtaining a token on its own.
 */
class McpOAuthEndToEndTest {

    public static class Tools {
        @AgentTool(description = "Names the authenticated caller and their scopes")
        public String whoami(CallerIdentity caller) {
            return caller.name() + " " + caller.attributes().get("scopes");
        }
    }

    private TestAuthorizationServer as;
    private AgentWeaveServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        as = new TestAuthorizationServer();
        as.clients.put("m2m", new TestAuthorizationServer.Client("m2m", "s3cret", "client_secret_basic", null));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        URI resource = URI.create(baseUrl + "/mcp");
        ProtectedResourceMetadata metadata = ProtectedResourceMetadata.builder(resource)
                .authorizationServer(URI.create(as.issuer())).scopesSupported("mcp:tools").build();
        BearerTokenAuthenticator auth = BearerTokenAuthenticator.builder(metadata, JwtTokenVerifier.builder()
                        .jwksUri(URI.create(as.issuer() + "/jwks")).issuer(as.issuer()).audience(resource).build())
                .requiredScopes("mcp:tools")
                .build();
        server = AgentWeaveServer.builder().host("127.0.0.1").port(port)
                .endpoints(new ProtectedResourceMetadataEndpoint(metadata),
                        McpToolServer.http(LocalTools.builder().scan(new Tools()).build()))
                .authenticator(auth)
                .start();
    }

    @AfterEach
    void close() {
        server.close();
        as.close();
    }

    @Test
    void clientDiscoversTheAuthorizationServerAndCallsTheProtectedTool() {
        OAuthClient oauth = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
                .build();
        try (McpClientConnector mcp = McpClientConnector.http(baseUrl).authorizer(oauth).build()) {
            mcp.start(); // 401 -> protected-resource metadata -> AS metadata -> token -> retry
            String who = mcp.invoke(ToolInvocation.of("whoami", Map.of())).text();
            // The caller is the token subject, with its scopes, as validated by BearerTokenAuthenticator.
            assertThat(who).isEqualTo("user-1 [mcp:tools]");
            assertThat(mcp.compliance().supports("authorization")).isTrue();
        }
        assertThat(oauth.cachedToken(URI.create(baseUrl + "/mcp"))).isPresent();
    }

    @Test
    void withoutAnAuthorizerTheClientFailsWithAuthentication() {
        try (McpClientConnector mcp = McpClientConnector.http(baseUrl).build()) {
            assertThat(mcp.compliance().unsupported()).contains("authorization");
            assertThatThrownBy(mcp::start).isInstanceOfSatisfying(AgentWeaveException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
    }

    @Test
    void anUnknownClientIsRejectedAndNotRetriedForever() {
        OAuthClient oauth = OAuthClient.builder().grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
                .registration(ClientRegistration.builder().clientId("nobody").clientSecret("wrong").build())
                .build();
        try (McpClientConnector mcp = McpClientConnector.http(baseUrl).authorizer(oauth).build()) {
            assertThatThrownBy(mcp::start).isInstanceOfSatisfying(AgentWeaveException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
    }
}
