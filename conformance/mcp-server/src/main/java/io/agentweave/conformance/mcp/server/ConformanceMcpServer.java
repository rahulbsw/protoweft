package io.agentweave.conformance.mcp.server;

import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.jetty.AgentWeaveServer;

/**
 * The server under test for the MCP conformance suite's server scenarios: AgentWeave's
 * {@link McpToolServer} over Streamable HTTP at {@code http://localhost:<port>/mcp}, hosted by
 * {@link AgentWeaveServer} (embedded Jetty), publishing the suite's fixtures.
 *
 * <p>Usage: {@code ConformanceMcpServer [port]} (default 8080, or {@code PORT}). Binds to
 * {@code localhost} only, which together with the localhost origin allow-list is what the
 * {@code dns-rebinding-protection} scenario expects of an unauthenticated local server.
 */
public final class ConformanceMcpServer {

    private ConformanceMcpServer() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        McpToolServer.Http mcp = McpToolServer.http(FixtureTools.create())
                .serverInfo("agentweave-mcp-conformance-server", "1.0.0")
                .resources(FixtureAuthoring.resources())
                .prompts(FixtureAuthoring.prompts())
                // Browsers on the local machine only; any other Origin gets 403 (DNS rebinding).
                .allowedOrigins("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*");
        AgentWeaveServer server = AgentWeaveServer.builder().host("localhost").port(port).endpoints(mcp).start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "conformance-mcp-server-stop"));
        System.err.println("MCP conformance server listening on " + server.baseUrl() + "/mcp");
        server.join();
    }
}
