package io.agentweave.mcp;

import io.agentweave.server.jetty.AgentWeaveServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Stopping the host must release the SDK's non-daemon session sweeper, or the JVM never exits. */
class McpToolServerShutdownTest {

    private static final String SWEEPER = "streamable-http-server-transport-session-sweeper";

    @Test
    void stoppingTheHostStopsTheSessionSweeper() throws Exception {
        McpToolServer.Http mcp = McpToolServer.http(TestTools.create());
        mcp.compliance(); // must not start a sweeper of its own
        long before = sweepers();

        try (AgentWeaveServer server = AgentWeaveServer.builder().port(0).endpoints(mcp).start();
             McpClientConnector client = McpClientConnector.http(server.baseUrl()).build()) {
            client.start();
            assertThat(client.listTools()).isNotEmpty();
            assertThat(sweepers()).isGreaterThan(before);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (sweepers() > before && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sweepers()).isEqualTo(before);
        mcp.close(); // idempotent after the host already closed it
    }

    private static long sweepers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith(SWEEPER)).count();
    }
}
