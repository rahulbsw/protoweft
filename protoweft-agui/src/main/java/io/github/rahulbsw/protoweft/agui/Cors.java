package io.github.rahulbsw.protoweft.agui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Set;

/**
 * Cross-origin access for browser frontends. Off unless origins are listed; {@code "*"} allows any
 * origin. Credentials (cookies) are never allowed cross-origin: browser clients authenticate with
 * an {@code Authorization} header instead.
 */
final class Cors {

    private static final String ALLOWED_HEADERS = "Content-Type, Accept, Authorization";

    private final Set<String> origins;

    Cors(List<String> origins) {
        this.origins = Set.copyOf(origins);
    }

    private boolean allows(String origin) {
        return origin != null && (origins.contains("*") || origins.contains(origin));
    }

    /**
     * Adds the CORS response headers to an actual request from an allowed origin. Others get none,
     * so the browser withholds the response; a cross-origin JSON POST never gets this far anyway,
     * because its preflight is refused.
     */
    void apply(HttpServletRequest req, HttpServletResponse resp) {
        String origin = req.getHeader("Origin");
        if (allows(origin)) {
            resp.setHeader("Access-Control-Allow-Origin", origins.contains("*") ? "*" : origin);
            resp.addHeader("Vary", "Origin");
        }
    }

    /** Answers a preflight: 204 with the allowed methods and headers, or 403. */
    void preflight(HttpServletRequest req, HttpServletResponse resp) {
        String origin = req.getHeader("Origin");
        if (!allows(origin)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        resp.setHeader("Access-Control-Allow-Origin", origins.contains("*") ? "*" : origin);
        resp.addHeader("Vary", "Origin");
        resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
        resp.setHeader("Access-Control-Max-Age", "600");
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
