package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * An {@link AgentHandler} behind a real {@link A2aAgentServer} in embedded Jetty, and an A2A
 * client over HTTP. {@code Bearer alice-token} authenticates as {@link #ALICE}; no header is anonymous.
 */
final class A2aHarness implements AutoCloseable {

    static final AgentDescriptor CARD = new AgentDescriptor("argi-agent", "An ARGI agent", "1.0.0",
            List.of(new AgentDescriptor.Skill("work", "Work", "Does work", List.of("work"), List.of())));

    /** Alice holds the {@code payments} scope. */
    static final CallerIdentity ALICE = new CallerIdentity("alice", Map.of("scopes", List.of("payments")));

    private final A2aAgentServer a2a;
    private final ProtoweftServer http;

    A2aHarness(AgentHandler handler) {
        int port = freePort();
        a2a = A2aAgentServer.builder(handler, CARD)
                .publicUrl("http://localhost:" + port)
                .agentTimeout(Duration.ofSeconds(20))
                .build();
        http = ProtoweftServer.builder().port(port)
                .authenticator(request -> request.bearerToken().filter("alice-token"::equals).isPresent()
                        ? AuthDecision.allow(ALICE)
                        : AuthDecision.allow(CallerIdentity.ANONYMOUS))
                .endpoints(a2a)
                .start();
    }

    A2aAgentConnector client(String token) {
        A2aAgentConnector.Builder builder = A2aAgentConnector.builder(http.baseUrl()).timeout(Duration.ofSeconds(20));
        if (token != null) {
            builder.credentials((p, t) -> List.of(new Credential.BearerToken(token)));
        }
        A2aAgentConnector client = builder.build();
        client.start();
        return client;
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
}
