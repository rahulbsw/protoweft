package io.agentweave.oauth;

import java.net.URI;

/**
 * Completes the interactive flow when the redirect URI is an HTTPS URL owned by the application
 * instead of {@link OAuthClient}'s loopback receiver.
 */
@FunctionalInterface
public interface AuthorizationCallback {

    /**
     * Sends the user to {@code authorizationRequest} and returns the full redirect URI the
     * authorization server sent them back to (carrying {@code code} and {@code state}, or an
     * {@code error}). {@link OAuthClient} verifies {@code state} itself.
     */
    URI authorize(URI authorizationRequest) throws Exception;
}
