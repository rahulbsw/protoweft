package io.agentweave.server;

import java.util.List;

/**
 * Implemented by protocol server components (MCP server, A2A server, UTCP manual provider)
 * to hand their HTTP endpoints to whichever host runs them.
 */
@FunctionalInterface
public interface ServerEndpointProvider {

    List<ServletEndpoint> endpoints();
}
