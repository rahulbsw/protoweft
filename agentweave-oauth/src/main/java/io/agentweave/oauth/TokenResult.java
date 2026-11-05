package io.agentweave.oauth;

import java.util.Optional;

/**
 * The outcome of {@link OAuthClient#handleUnauthorized}: either a fresh access token to retry the
 * request with, or the reason the client gave up (the original 401/403 should then be surfaced).
 */
public final class TokenResult {

    private final String accessToken;
    private final String failure;

    private TokenResult(String accessToken, String failure) {
        this.accessToken = accessToken;
        this.failure = failure;
    }

    static TokenResult retry(String accessToken) {
        return new TokenResult(accessToken, null);
    }

    static TokenResult giveUp(String reason) {
        return new TokenResult(null, reason);
    }

    /** Whether the request should be retried with {@link #accessToken()}. */
    public boolean shouldRetry() {
        return accessToken != null;
    }

    /** The token to send as {@code Authorization: Bearer}, when {@link #shouldRetry()}. */
    public Optional<String> accessToken() {
        return Optional.ofNullable(accessToken);
    }

    /** Why no token could be obtained, when not {@link #shouldRetry()}. */
    public Optional<String> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public String toString() {
        return shouldRetry() ? "TokenResult[retry, token=***]" : "TokenResult[giveUp: " + failure + "]";
    }
}
