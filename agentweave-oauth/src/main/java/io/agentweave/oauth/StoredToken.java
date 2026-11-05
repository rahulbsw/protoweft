package io.agentweave.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tokens issued for one resource. {@link #toString()} never prints a token.
 *
 * @param accessToken  the bearer access token
 * @param refreshToken the refresh token, or {@code null}
 * @param expiresAt    when the access token expires, or {@code null} if the server did not say
 * @param scopes       the granted scopes (as reported by the server, else as requested)
 */
public record StoredToken(String accessToken, String refreshToken, Instant expiresAt, Set<String> scopes) {

    public StoredToken {
        Objects.requireNonNull(accessToken, "accessToken");
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** Whether the access token expires within {@code margin} of {@code now}. */
    public boolean expired(Instant now, Duration margin) {
        return expiresAt != null && !now.plus(margin).isBefore(expiresAt);
    }

    /** Whether the granted scopes include every scope in a space-delimited list ({@code null} = none needed). */
    public boolean covers(String scope) {
        return scope == null || scopes.containsAll(split(scope));
    }

    StoredToken withoutAccess() {
        return new StoredToken("", refreshToken, Instant.EPOCH, scopes);
    }

    static Set<String> split(String scope) {
        return scope == null ? Set.of()
                : Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public String toString() {
        return "StoredToken[accessToken=***, refreshToken=" + (refreshToken == null ? "none" : "***")
                + ", expiresAt=" + expiresAt + ", scopes=" + scopes + "]";
    }
}
