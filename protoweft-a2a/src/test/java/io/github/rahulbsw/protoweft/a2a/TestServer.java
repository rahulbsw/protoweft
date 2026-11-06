package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Runs {@link TestAgent} behind an {@link A2aAgentServer} in embedded Jetty, plus a push
 * webhook. Callers: no Authorization header → anonymous; {@code Bearer alice-token} → alice;
 * {@code Bearer bob-token} → bob; anything else → 401.
 */
final class TestServer implements AutoCloseable {

    /** One received push notification. */
    record Push(String token, String body) {
    }

    static final InboundAuthenticator AUTH = request -> {
        if (request.header("Authorization").isEmpty()) {
            return AuthDecision.allow(CallerIdentity.ANONYMOUS);
        }
        return switch (request.bearerToken().orElse("")) {
            case "alice-token" -> AuthDecision.allow(CallerIdentity.of("alice"));
            case "bob-token" -> AuthDecision.allow(CallerIdentity.of("bob"));
            default -> AuthDecision.unauthenticated("bad token");
        };
    };

    final TestAgent agent = new TestAgent();
    final BlockingQueue<Push> pushes = new LinkedBlockingQueue<>();
    final A2aAgentServer a2a;
    final ProtoweftServer http;

    TestServer() {
        this(b -> { });
    }

    TestServer(Consumer<A2aAgentServer.Builder> customize) {
        int port = freePort();
        A2aAgentServer.Builder builder = A2aAgentServer.builder(agent, TestAgent.DESCRIPTOR)
                .publicUrl("http://localhost:" + port)
                .extendedDescriptor(TestAgent.EXTENDED)
                .insecurePushTargets(true)
                .agentTimeout(java.time.Duration.ofSeconds(10));
        customize.accept(builder);
        a2a = builder.build();
        http = ProtoweftServer.builder().port(port).authenticator(AUTH)
                .endpoints(a2a)
                .endpoint(new ServletEndpoint("webhook", ProtocolId.A2A, List.of("/webhook"), new Webhook(pushes), false))
                .start();
    }

    String baseUrl() {
        return http.baseUrl();
    }

    A2aAgentConnector.Builder client() {
        return A2aAgentConnector.builder(baseUrl()).timeout(java.time.Duration.ofSeconds(15));
    }

    A2aAgentConnector.Builder client(String token) {
        return client().credentials((p, t) -> List.of(new Credential.BearerToken(token)));
    }

    static CredentialProvider bearer(String token) {
        return (p, t) -> List.of(new Credential.BearerToken(token));
    }

    @Override
    public void close() {
        http.close();
        a2a.close();
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Webhook extends HttpServlet {
        private final transient BlockingQueue<Push> pushes;

        Webhook(BlockingQueue<Push> pushes) {
            this.pushes = pushes;
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            pushes.add(new Push(req.getHeader("X-A2A-Notification-Token"),
                    new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            resp.setStatus(200);
        }
    }
}
