package io.agentweave.core.server;

import java.util.Objects;

/** The outcome of authenticating an {@link InboundRequest}. */
public sealed interface AuthDecision {

    static AuthDecision allow(CallerIdentity caller) {
        return new Allow(caller);
    }

    /** Rejects with HTTP 401: credentials missing or invalid. */
    static AuthDecision unauthenticated(String reason) {
        return new Deny(401, reason);
    }

    /** Rejects with HTTP 403: authenticated but not permitted. */
    static AuthDecision forbidden(String reason) {
        return new Deny(403, reason);
    }

    record Allow(CallerIdentity caller) implements AuthDecision {
        public Allow {
            Objects.requireNonNull(caller, "caller");
        }
    }

    /**
     * @param status the HTTP status to answer with (401 or 403)
     * @param reason a short message safe to show the caller
     */
    record Deny(int status, String reason) implements AuthDecision {
        public Deny {
            if (status != 401 && status != 403) {
                throw new IllegalArgumentException("status must be 401 or 403");
            }
            reason = reason == null ? "" : reason;
        }
    }
}
