package io.agentweave.oauth;

/**
 * Validates an access token presented to a resource server (OAuth 2.1 §5.2). Implement it for
 * opaque tokens (for example with token introspection, RFC 7662); {@link JwtTokenVerifier} covers
 * JWT access tokens.
 *
 * <p>{@link BearerTokenAuthenticator} additionally requires {@link VerifiedToken#audiences()} to
 * contain the canonical resource URI, so introspection-based verifiers must report {@code aud}.
 */
@FunctionalInterface
public interface TokenVerifier {

    /**
     * @throws InvalidTokenException when the token is not valid (expired, bad signature, wrong
     *                               issuer or audience, unknown); the message is shown to the caller
     */
    VerifiedToken verify(String token) throws InvalidTokenException;
}
