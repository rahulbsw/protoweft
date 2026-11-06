package io.github.rahulbsw.protoweft.server;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import jakarta.servlet.http.HttpServlet;

import java.util.List;
import java.util.Objects;

/**
 * One HTTP endpoint contributed by a protocol server module (MCP, A2A, UTCP), described
 * independently of the container that hosts it. Embedded Jetty
 * ({@code protoweft-server-jetty}) and the Spring Boot starter both mount these.
 *
 * @param name           a unique, stable name, for example {@code "mcp"}
 * @param protocol       the protocol served, used to build {@link io.github.rahulbsw.protoweft.core.server.InboundRequest}s
 * @param urlPatterns    servlet URL patterns, for example {@code "/mcp"} or {@code "/utcp/*"}
 * @param servlet        the servlet handling the requests
 * @param asyncSupported whether the servlet uses async processing (streaming / SSE); hosts
 *                       must enable it on the registration and on the auth filter
 */
public record ServletEndpoint(String name, ProtocolId protocol, List<String> urlPatterns, HttpServlet servlet,
                              boolean asyncSupported) {

    public ServletEndpoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(servlet, "servlet");
        urlPatterns = List.copyOf(urlPatterns);
        if (urlPatterns.isEmpty()) {
            throw new IllegalArgumentException("at least one URL pattern is required");
        }
    }
}
