package io.agentweave.server;

import io.agentweave.core.ProtocolId;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.server.InboundRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the configured {@link InboundAuthenticator} before an endpoint's servlet. Denied
 * requests get a JSON error with status 401 or 403; allowed requests carry the caller as a
 * request attribute that servlets read with {@link #caller(HttpServletRequest)}.
 *
 * <p>Hosts install one instance per {@link ServletEndpoint}, with async support enabled
 * whenever the endpoint's servlet is async.
 */
public final class InboundAuthFilter implements Filter {

    /** Request attribute holding the authenticated {@link CallerIdentity}. */
    public static final String CALLER_ATTRIBUTE = "io.agentweave.caller";

    private final InboundAuthenticator authenticator;
    private final ProtocolId protocol;

    public InboundAuthFilter(InboundAuthenticator authenticator, ProtocolId protocol) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    /** The caller established by this filter, or anonymous if the filter did not run. */
    public static CallerIdentity caller(HttpServletRequest request) {
        return request.getAttribute(CALLER_ATTRIBUTE) instanceof CallerIdentity c ? c : CallerIdentity.ANONYMOUS;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        AuthDecision decision;
        try {
            decision = authenticator.authenticate(toInbound(request));
        } catch (RuntimeException e) {
            decision = AuthDecision.unauthenticated("authentication failed");
        }
        if (decision instanceof AuthDecision.Deny deny) {
            response.setStatus(deny.status());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            if (deny.status() == 401) {
                response.setHeader("WWW-Authenticate", "Bearer");
            }
            response.getWriter().write("{\"error\":\"" + escape(deny.reason()) + "\"}");
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, ((AuthDecision.Allow) decision).caller());
        chain.doFilter(request, response);
    }

    private InboundRequest toInbound(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return new InboundRequest(protocol, request.getMethod(), path, headers, request.getRemoteAddr());
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c >= 0x20) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
