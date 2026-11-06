package io.github.rahulbsw.protoweft.core.auth;

import java.net.URI;
import java.util.Optional;

/**
 * Authorizes outgoing HTTP requests that may need credentials obtained on demand, such as
 * OAuth access tokens discovered from a server's 401 challenge. HTTP connectors (for example
 * MCP over Streamable HTTP) call it on every request and on every 401 or 403.
 * {@code protoweft-oauth}'s {@code OAuthClient} implements it.
 */
public interface HttpAuthorizer {

    /** The {@code Authorization} header value for a request to {@code resource}, if one is available. */
    Optional<String> authorization(URI resource);

    /**
     * Handles a 401 or 403 from {@code resource}. Returns {@code true} if new credentials were
     * obtained and the request should be retried, {@code false} to fail it.
     *
     * @param wwwAuthenticate the response's {@code WWW-Authenticate} header, or {@code null}
     */
    boolean onChallenge(URI resource, int status, String wwwAuthenticate);
}
