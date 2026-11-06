package io.github.rahulbsw.protoweft.conformance.a2a;

import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;

import java.util.List;

/**
 * The system under test for the A2A TCK: Protoweft's {@link A2aAgentServer} (A2A 1.0,
 * JSON-RPC binding) on {@link ProtoweftServer} (embedded Jetty), running {@link TckAgent}.
 *
 * <p>Usage: {@code ConformanceA2aSut [port]} (default 9999, or {@code PORT}). The card is at
 * {@code /.well-known/agent-card.json} and JSON-RPC at {@code /}. The card declares only what
 * the server implements: the JSON-RPC interface (no gRPC or HTTP+JSON), streaming, and push
 * notifications. {@code TCK_STREAMING_TIMEOUT} (seconds, default 2.0) sets how long the
 * resubscribe task runs (twice that).
 */
public final class ConformanceA2aSut {

    private ConformanceA2aSut() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "9999"));
        double streamingTimeout = Double.parseDouble(System.getenv().getOrDefault("TCK_STREAMING_TIMEOUT", "2.0"));
        AgentDescriptor descriptor = new AgentDescriptor("Protoweft A2A TCK SUT",
                "Protoweft A2aAgentServer answering the A2A TCK's conformance messages", "1.0.0",
                List.of(new AgentDescriptor.Skill("tck", "TCK Conformance",
                        "Handles TCK conformance test messages", List.of("tck"), List.of())));
        A2aAgentServer agent = A2aAgentServer.builder(new TckAgent(streamingTimeout), descriptor)
                .publicUrl("http://localhost:" + port)
                // JSON-RPC at the root, as in a2a-java's SUT: the TCK's httpx client posts to
                // "<interface url>/", so an interface path without a trailing slash (/a2a) would
                // be called as /a2a/ and miss the exact servlet mapping.
                .path("/")
                .streaming(true)
                .pushNotifications(true)
                // The TCK's push webhook listens on plain http on localhost.
                .insecurePushTargets(true)
                .build();
        ProtoweftServer server = ProtoweftServer.builder().host("localhost").port(port).endpoints(agent).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            agent.close();
        }, "conformance-a2a-sut-stop"));
        System.err.println("A2A TCK SUT listening on " + server.baseUrl() + ", card at "
                + server.baseUrl() + A2aAgentServer.AGENT_CARD_PATH);
        server.join();
    }
}
