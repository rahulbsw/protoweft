package io.agentweave.server.jetty;

import io.agentweave.core.ProtocolId;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.server.InboundAuthFilter;
import io.agentweave.server.ServletEndpoint;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWeaveServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final HttpServlet WHOAMI = new HttpServlet() {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("hello " + InboundAuthFilter.caller(req).name());
        }
    };

    @Test
    void servesEndpointsAndPassesTheAuthenticatedCaller() throws Exception {
        try (AgentWeaveServer server = AgentWeaveServer.builder().port(0)
                .endpoint(new ServletEndpoint("whoami", ProtocolId.MCP, List.of("/whoami"), WHOAMI, false))
                .authenticator(req -> req.bearerToken().filter("s3cret"::equals)
                        .map(t -> AuthDecision.allow(CallerIdentity.of("alice")))
                        .orElse(AuthDecision.unauthenticated("missing or invalid token")))
                .start()) {

            HttpResponse<String> ok = get(server, "/whoami", "Bearer s3cret");
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("hello alice");

            HttpResponse<String> denied = get(server, "/whoami", null);
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.body()).isEqualTo("{\"error\":\"missing or invalid token\"}");
            assertThat(denied.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        }
    }

    @Test
    void defaultAuthenticatorAllowsAnonymous() throws Exception {
        try (AgentWeaveServer server = AgentWeaveServer.builder().port(0)
                .endpoint(new ServletEndpoint("whoami", ProtocolId.MCP, List.of("/whoami"), WHOAMI, false))
                .start()) {
            assertThat(get(server, "/whoami", null).body()).isEqualTo("hello anonymous");
            assertThat(get(server, "/other", null).statusCode()).isEqualTo(404);
        }
    }

    private static HttpResponse<String> get(AgentWeaveServer server, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET();
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void denyChallengeIsSentAsWwwAuthenticate() throws Exception {
        String challenge = "Bearer resource_metadata=\"https://example.com/.well-known/oauth-protected-resource\"";
        try (AgentWeaveServer server = AgentWeaveServer.builder().port(0)
                .endpoint(new ServletEndpoint("whoami", ProtocolId.MCP, List.of("/whoami"), WHOAMI, false))
                .authenticator(req -> AuthDecision.unauthenticated("token required", challenge))
                .start()) {
            HttpResponse<String> denied = get(server, "/whoami", null);
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.headers().firstValue("WWW-Authenticate")).contains(challenge);
        }
    }
}
