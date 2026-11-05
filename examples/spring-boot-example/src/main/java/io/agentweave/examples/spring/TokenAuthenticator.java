package io.agentweave.examples.spring;

import io.agentweave.a2a.A2aAgentServer;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.server.InboundRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Guards every AgentWeave endpoint with {@code Authorization: Bearer <example.api-token>}. The
 * public A2A agent card stays readable so clients can discover the agent.
 */
@Component
public class TokenAuthenticator implements InboundAuthenticator {

    private final byte[] token;

    public TokenAuthenticator(@Value("${example.api-token}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public AuthDecision authenticate(InboundRequest request) {
        if (request.method().equals("GET") && request.path().equals(A2aAgentServer.AGENT_CARD_PATH)) {
            return AuthDecision.allow(CallerIdentity.ANONYMOUS);
        }
        return request.bearerToken()
                .filter(t -> MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token))
                .<AuthDecision>map(t -> AuthDecision.allow(CallerIdentity.of("demo-client")))
                .orElseGet(() -> AuthDecision.unauthenticated("bearer token required"));
    }
}
