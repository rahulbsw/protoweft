package io.github.rahulbsw.protoweft.core.server;

/**
 * Decides whether an incoming request to any Protoweft server endpoint (MCP, A2A, UTCP)
 * may proceed, and who the caller is. Every server endpoint calls the configured
 * authenticator before handling a request, so one implementation protects all protocols.
 *
 * <p>Protoweft ships no identity provider: validate API keys, bearer tokens, or mTLS
 * attributes here, or delegate to the host framework (for example Spring Security).
 */
@FunctionalInterface
public interface InboundAuthenticator {

    /** Allows every request as {@link CallerIdentity#ANONYMOUS}. */
    InboundAuthenticator ALLOW_ALL = request -> AuthDecision.allow(CallerIdentity.ANONYMOUS);

    AuthDecision authenticate(InboundRequest request);
}
