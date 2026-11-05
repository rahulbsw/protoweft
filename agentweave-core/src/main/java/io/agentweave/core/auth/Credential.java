package io.agentweave.core.auth;

import java.util.Objects;

/**
 * A credential a connector can present to a peer. Which kinds a connector accepts, and how
 * it presents them, depends on the protocol and transport; a connector rejects a kind it
 * cannot use with {@link io.agentweave.core.ErrorCategory#AUTHENTICATION}.
 */
public sealed interface Credential {

    /** An OAuth 2.0 / bearer access token. */
    record BearerToken(String token) implements Credential {
        public BearerToken {
            Objects.requireNonNull(token, "token");
        }

        @Override
        public String toString() {
            return "BearerToken[***]";
        }
    }

    /**
     * A named secret, such as an API key.
     *
     * @param name  where the secret goes, interpreted by the connector (for example an
     *              environment variable or header name)
     * @param value the secret
     */
    record NamedSecret(String name, String value) implements Credential {
        public NamedSecret {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "NamedSecret[name=" + name + ", value=***]";
        }
    }
}
