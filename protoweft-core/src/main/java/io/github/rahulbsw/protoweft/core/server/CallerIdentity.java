package io.github.rahulbsw.protoweft.core.server;

import java.util.Map;
import java.util.Objects;

/**
 * Who is calling a server endpoint, as established by an {@link InboundAuthenticator}.
 *
 * @param name       a stable identifier for the caller, for example a user or client id
 * @param attributes extra facts the authenticator wants handlers to see (scopes, tenant, …)
 */
public record CallerIdentity(String name, Map<String, Object> attributes) {

    /** The identity used when no authenticator is configured or it allows anonymous access. */
    public static final CallerIdentity ANONYMOUS = new CallerIdentity("anonymous", Map.of());

    public CallerIdentity {
        Objects.requireNonNull(name, "name");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static CallerIdentity of(String name) {
        return new CallerIdentity(name, Map.of());
    }

    public boolean isAnonymous() {
        return this.equals(ANONYMOUS);
    }
}
