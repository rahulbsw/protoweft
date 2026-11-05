package io.agentweave.oauth;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where {@link OAuthClient} keeps tokens. Implementations must be thread-safe and should protect
 * tokens at rest (MCP: clients MUST store tokens securely); the default keeps them in memory only.
 */
public interface TokenStore {

    Optional<StoredToken> load(Key key);

    void save(Key key, StoredToken token);

    void remove(Key key);

    /** A thread-safe store that keeps tokens in memory for the life of the process. */
    static TokenStore inMemory() {
        Map<Key, StoredToken> tokens = new ConcurrentHashMap<>();
        return new TokenStore() {
            @Override
            public Optional<StoredToken> load(Key key) {
                return Optional.ofNullable(tokens.get(key));
            }

            @Override
            public void save(Key key, StoredToken token) {
                tokens.put(key, token);
            }

            @Override
            public void remove(Key key) {
                tokens.remove(key);
            }
        };
    }

    /**
     * Tokens are bound to one resource, issued by one authorization server to one client.
     *
     * @param resource            the canonical resource URI
     * @param authorizationServer the issuer identifier
     * @param clientId            the client id used at that authorization server
     */
    record Key(String resource, String authorizationServer, String clientId) {
        public Key {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(authorizationServer, "authorizationServer");
            Objects.requireNonNull(clientId, "clientId");
        }
    }
}
