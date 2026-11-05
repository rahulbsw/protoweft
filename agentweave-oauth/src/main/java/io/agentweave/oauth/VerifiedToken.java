package io.agentweave.oauth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@link TokenVerifier} established about a valid access token.
 *
 * @param subject   the {@code sub} claim, or {@code null}
 * @param clientId  the {@code client_id} (or {@code azp}) claim, or {@code null}
 * @param audiences the {@code aud} values
 * @param scopes    the granted scopes
 * @param claims    all claims, for handlers that need more (null values dropped)
 */
public record VerifiedToken(String subject, String clientId, Set<String> audiences, Set<String> scopes,
                            Map<String, Object> claims) {

    public VerifiedToken {
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        Map<String, Object> copy = new LinkedHashMap<>();
        if (claims != null) {
            claims.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            });
        }
        claims = java.util.Collections.unmodifiableMap(copy);
    }

    /** The caller name: {@code sub}, else {@code client_id}. */
    public String principal() {
        return subject != null ? subject : Objects.requireNonNullElse(clientId, "oauth-client");
    }
}
