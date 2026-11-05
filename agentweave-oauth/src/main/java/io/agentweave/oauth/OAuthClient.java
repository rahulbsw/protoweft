package io.agentweave.oauth;

import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import io.agentweave.core.AgentWeaveException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An OAuth 2.1 client for protected HTTP resources, following the client side of the MCP
 * authorization specification (2025-11-25) and usable by any HTTP connector.
 *
 * <p>On a 401 or 403 the connector calls {@link #handleUnauthorized}; the client parses the
 * {@code WWW-Authenticate} challenge, discovers the Protected Resource Metadata (RFC 9728) and the
 * authorization server metadata (RFC 8414 / OIDC Discovery), registers (pre-registered, Client ID
 * Metadata Document, or Dynamic Client Registration), and obtains a token with the
 * {@code resource} parameter (RFC 8707). Before every request the connector sends
 * {@link #cachedToken} as {@code Authorization: Bearer}; tokens never go in a query string.
 *
 * <p>Thread-safe. Flows for the same resource are serialized; different resources proceed in parallel.
 */
public final class OAuthClient implements io.agentweave.core.auth.HttpAuthorizer {

    /** The grant used to obtain tokens. */
    public enum Grant {
        /** Machine-to-machine: the client acts on its own behalf (needs a confidential client). */
        CLIENT_CREDENTIALS,
        /** Interactive: the user authorizes in a browser; PKCE S256 and {@code state} are always used. */
        AUTHORIZATION_CODE
    }

    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final Grant grant;
    private final TokenStore store;
    private final BrowserLauncher browser;
    private final int loopbackPort;
    private final URI redirectUri;
    private final AuthorizationCallback callback;
    private final Duration authorizationTimeout;
    private final int maxStepUpRetries;
    private final Duration retryWindow;
    private final Clock clock;
    private final Discovery discovery;
    private final Registrar registrar;
    private final TokenRequests tokens;
    private final AuthorizationCodeFlow codeFlow;
    private final Map<URI, ResourceState> states = new ConcurrentHashMap<>();
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
    private volatile int lastLoopbackPort;

    private OAuthClient(Builder b) {
        this.grant = b.grant;
        this.store = b.store;
        this.browser = b.browser;
        this.loopbackPort = b.loopbackPort;
        this.redirectUri = b.redirectUri;
        this.callback = b.callback;
        this.authorizationTimeout = b.authorizationTimeout;
        this.maxStepUpRetries = b.maxStepUpRetries;
        this.retryWindow = b.retryWindow;
        this.clock = b.clock;
        Http http = new Http(b.httpClient, b.requestTimeout);
        this.discovery = new Discovery(http);
        this.registrar = new Registrar(http, b.registration);
        this.tokens = new TokenRequests(http, clock);
        this.codeFlow = new AuthorizationCodeFlow(tokens);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The canonical form of a resource URI as sent in {@code resource} and expected as token
     * audience: lowercase scheme and host, no fragment, no default port, no trailing slash.
     */
    public static URI canonicalResource(URI resource) {
        return Uris.canonical(resource);
    }

    /** {@code Bearer <token>} from the cache (refreshing if possible); no interactive flow. */
    @Override
    public Optional<String> authorization(URI resource) {
        return cachedToken(resource).map(token -> "Bearer " + token);
    }

    /** Runs discovery, registration and the configured grant for a 401/403 challenge. */
    @Override
    public boolean onChallenge(URI resource, int status, String wwwAuthenticate) {
        return handleUnauthorized(resource, wwwAuthenticate, status).shouldRetry();
    }

    /**
     * A valid token for {@code resource} that is already held, refreshing it with its refresh
     * token when it has expired. Never starts discovery or an interactive flow.
     */
    public Optional<String> cachedToken(URI resource) {
        ResourceState st = states.get(Uris.canonical(resource));
        if (st == null || st.key == null) {
            return Optional.empty();
        }
        Optional<StoredToken> stored = store.load(st.key);
        if (stored.isPresent() && !stored.get().expired(clock.instant(), EXPIRY_MARGIN)) {
            return Optional.of(stored.get().accessToken());
        }
        if (stored.isEmpty() || stored.get().refreshToken() == null) {
            return Optional.empty();
        }
        st.lock.lock();
        try {
            StoredToken current = store.load(st.key).orElse(null);
            if (current != null && !current.expired(clock.instant(), EXPIRY_MARGIN)) {
                return Optional.of(current.accessToken());
            }
            return current == null || current.refreshToken() == null ? Optional.empty()
                    : Optional.ofNullable(tryRefresh(st, current));
        } finally {
            st.lock.unlock();
        }
    }

    /**
     * A token for {@code resource} that satisfies {@code challenge}: a held one when valid and
     * covering the challenged scope, else a refreshed or newly obtained one (which may run
     * discovery, registration and, for {@link Grant#AUTHORIZATION_CODE}, the browser flow).
     *
     * @param challenge the server's challenge, or {@link Challenge#NONE} to authorize up front
     * @throws AgentWeaveException {@code AUTHENTICATION} when no token can be obtained
     */
    public String accessToken(URI resource, Challenge challenge) {
        ResourceState st = states.computeIfAbsent(Uris.canonical(resource), ResourceState::new);
        st.lock.lock();
        try {
            return obtain(st, challenge == null ? Challenge.NONE : challenge, false, false);
        } finally {
            st.lock.unlock();
        }
    }

    /**
     * Reacts to a 401 or 403 from {@code resource}. A 401 drops the rejected token and refreshes or
     * re-authorizes; a 403 with {@code error="insufficient_scope"} re-authorizes with the
     * challenged scope (step-up). Each (resource, status, scope) gets at most
     * {@code maxStepUpRetries} re-authorizations (one more for 401) within the retry window, after
     * which the client gives up.
     *
     * @param wwwAuthenticate the response's {@code WWW-Authenticate} header, or {@code null}
     * @param status          the HTTP status
     */
    public TokenResult handleUnauthorized(URI resource, String wwwAuthenticate, int status) {
        Challenge challenge = Challenge.parse(wwwAuthenticate);
        if (status == 403 && !challenge.insufficientScope()) {
            return TokenResult.giveUp("403 Forbidden without insufficient_scope; not an authorization problem");
        }
        if (status != 401 && status != 403) {
            return TokenResult.giveUp("HTTP " + status + " is not an authorization challenge");
        }
        URI canonical;
        try {
            canonical = Uris.canonical(resource);
        } catch (AgentWeaveException e) {
            return TokenResult.giveUp(e.getMessage());
        }
        int limit = status == 401 ? maxStepUpRetries + 1 : maxStepUpRetries;
        String attemptKey = canonical + " " + status + " " + String.join(" ",
                new java.util.TreeSet<>(StoredToken.split(challenge.scope())));
        if (!tryAttempt(attemptKey, limit)) {
            return TokenResult.giveUp("giving up after " + limit + " authorization attempt(s) for " + canonical
                    + (status == 403 ? " with scope '" + challenge.scope() + "'" : ""));
        }
        ResourceState st = states.computeIfAbsent(canonical, ResourceState::new);
        st.lock.lock();
        try {
            boolean rejected = st.key != null;
            return TokenResult.retry(obtain(st, challenge, rejected, status == 403));
        } catch (AgentWeaveException e) {
            return TokenResult.giveUp(e.getMessage());
        } finally {
            st.lock.unlock();
        }
    }

    /** Must hold {@code st.lock}. */
    private String obtain(ResourceState st, Challenge challenge, boolean rejected, boolean stepUp) {
        discover(st, challenge.resourceMetadata());
        String scope = challenge.scope() != null ? challenge.scope()
                : st.prm.metadata().scopesSupported().isEmpty() ? null
                : String.join(" ", st.prm.metadata().scopesSupported());
        URI resource = st.prm.metadata().resource();
        if (st.key == null) {
            // A pre-registered or CIMD identity is known without registering, so a token persisted
            // by an earlier run can be found and reused.
            Registrar.RegisteredClient known = registrar.known(st.as, grant);
            if (known != null) {
                st.key = key(st, known);
            }
        }
        if (!stepUp && st.key != null) {
            Optional<StoredToken> stored = store.load(st.key);
            if (stored.isPresent() && stored.get().covers(scope)) {
                StoredToken t = stored.get();
                if (!rejected && !t.accessToken().isEmpty() && !t.expired(clock.instant(), EXPIRY_MARGIN)) {
                    return t.accessToken();
                }
                if (t.refreshToken() != null) {
                    String refreshed = tryRefresh(st, t);
                    if (refreshed != null) {
                        return refreshed;
                    }
                }
            } else if (stored.isPresent() && rejected) {
                store.remove(st.key);
            }
        }
        StoredToken issued = grant == Grant.CLIENT_CREDENTIALS
                ? clientCredentials(st, scope, resource) : authorizationCode(st, scope, resource);
        store.save(st.key, issued);
        return issued.accessToken();
    }

    private void discover(ResourceState st, URI resourceMetadata) {
        boolean stale = st.prm == null
                || (resourceMetadata != null && !resourceMetadata.equals(st.prm.url()));
        if (!stale) {
            return;
        }
        Discovery.Prm prm = discovery.protectedResource(st.resource, resourceMetadata);
        AuthorizationServerMetadata as = discovery.authorizationServer(prm.metadata());
        if (grant == Grant.AUTHORIZATION_CODE) {
            AuthorizationCodeFlow.requirePkce(as);
        }
        if (st.as != null && !st.as.getIssuer().equals(as.getIssuer())) {
            st.key = null;
            st.client = null;
        }
        st.prm = prm;
        st.as = as;
    }

    private StoredToken clientCredentials(ResourceState st, String scope, URI resource) {
        Registrar.RegisteredClient client = registrar.clientFor(st.as, grant, null);
        st.key = key(st, client);
        return tokens.request(st.as, client, new ClientCredentialsGrant(), scope, resource, null, null);
    }

    private StoredToken authorizationCode(ResourceState st, String scope, URI resource) {
        if (redirectUri != null) {
            Registrar.RegisteredClient client = registrar.clientFor(st.as, grant, redirectUri);
            st.key = key(st, client);
            return codeFlow.run(st.as, client, scope, resource, redirectUri,
                    AuthorizationCodeFlow.viaCallback(callback));
        }
        int port = loopbackPort != 0 ? loopbackPort : lastLoopbackPort;
        try (LoopbackReceiver receiver = LoopbackReceiver.start(port, loopbackPort == 0)) {
            lastLoopbackPort = receiver.port();
            URI redirect = receiver.redirectUri();
            Registrar.RegisteredClient client = registrar.clientFor(st.as, grant, redirect);
            st.key = key(st, client);
            return codeFlow.run(st.as, client, scope, resource, redirect, (request, state) -> {
                receiver.expect(state);
                browser.open(request);
                return receiver.await(authorizationTimeout);
            });
        }
    }

    /** Refreshes with the stored refresh token; on failure drops the token and returns {@code null}. */
    private String tryRefresh(ResourceState st, StoredToken current) {
        try {
            StoredToken refreshed = tokens.request(st.as, st.client,
                    new RefreshTokenGrant(new RefreshToken(current.refreshToken())), null,
                    st.prm.metadata().resource(), current.refreshToken(), current.scopes());
            store.save(st.key, refreshed);
            return refreshed.accessToken();
        } catch (AgentWeaveException e) {
            store.remove(st.key);
            return null;
        }
    }

    private static TokenStore.Key key(ResourceState st, Registrar.RegisteredClient client) {
        st.client = client;
        return new TokenStore.Key(st.prm.metadata().resource().toString(), st.as.getIssuer().getValue(),
                client.clientId());
    }

    /** Counts an attempt; attempts older than the retry window are forgotten. */
    private boolean tryAttempt(String key, int limit) {
        long now = clock.millis();
        long[] entry = attempts.compute(key, (k, v) -> {
            if (v == null || now - v[1] > retryWindow.toMillis()) {
                return new long[] {1, now};
            }
            return new long[] {v[0] + 1, now};
        });
        return entry[0] <= limit;
    }

    /** Per-resource discovery results and the token key in use. Guarded by {@link #lock}. */
    private static final class ResourceState {
        final URI resource;
        final ReentrantLock lock = new ReentrantLock();
        Discovery.Prm prm;
        AuthorizationServerMetadata as;
        Registrar.RegisteredClient client;
        volatile TokenStore.Key key;

        ResourceState(URI resource) {
            this.resource = resource;
        }
    }

    public static final class Builder {
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Grant grant = Grant.AUTHORIZATION_CODE;
        private ClientRegistration registration = ClientRegistration.dynamic();
        private TokenStore store = TokenStore.inMemory();
        private BrowserLauncher browser = BrowserLauncher.system();
        private int loopbackPort;
        private URI redirectUri;
        private AuthorizationCallback callback;
        private Duration authorizationTimeout = Duration.ofMinutes(5);
        private int maxStepUpRetries = 2;
        private Duration retryWindow = Duration.ofMinutes(1);
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * The client for metadata, registration and token requests. It must not follow redirects
         * (the default client does not).
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** {@link Grant#AUTHORIZATION_CODE} (default) or {@link Grant#CLIENT_CREDENTIALS}. */
        public Builder grant(Grant grant) {
            this.grant = Objects.requireNonNull(grant, "grant");
            return this;
        }

        public Builder registration(ClientRegistration registration) {
            this.registration = Objects.requireNonNull(registration, "registration");
            return this;
        }

        /** Where tokens are kept; default {@link TokenStore#inMemory()}. */
        public Builder tokenStore(TokenStore store) {
            this.store = Objects.requireNonNull(store, "store");
            return this;
        }

        /** Opens the authorization URL; default {@link BrowserLauncher#system()}. */
        public Builder browserLauncher(BrowserLauncher browser) {
            this.browser = Objects.requireNonNull(browser, "browser");
            return this;
        }

        /**
         * A fixed port for the loopback redirect URI {@code http://127.0.0.1:<port>/callback}
         * (needed when the redirect URI is pre-registered with a port). Default 0: an ephemeral
         * port, reused across flows while it stays free.
         */
        public Builder loopbackPort(int port) {
            if (port < 0 || port > 65535) {
                throw Uris.config("invalid loopback port " + port);
            }
            this.loopbackPort = port;
            return this;
        }

        /** An HTTPS redirect URI handled by the application instead of the loopback receiver. */
        public Builder redirectUri(URI httpsRedirectUri, AuthorizationCallback callback) {
            Objects.requireNonNull(httpsRedirectUri, "httpsRedirectUri");
            if (!"https".equalsIgnoreCase(httpsRedirectUri.getScheme()) || httpsRedirectUri.getRawFragment() != null) {
                throw Uris.config("redirect URI must be https without a fragment (or use the loopback receiver)");
            }
            this.redirectUri = httpsRedirectUri;
            this.callback = Objects.requireNonNull(callback, "callback");
            return this;
        }

        /** How long to wait for the user to complete the browser flow; default 5 minutes. */
        public Builder authorizationTimeout(Duration timeout) {
            this.authorizationTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Step-up re-authorizations allowed per resource and scope set within the retry window; default 2. */
        public Builder maxStepUpRetries(int retries) {
            if (retries < 0) {
                throw Uris.config("maxStepUpRetries must be >= 0");
            }
            this.maxStepUpRetries = retries;
            return this;
        }

        /** Attempts further apart than this start a fresh count; default 1 minute. */
        public Builder retryWindow(Duration window) {
            this.retryWindow = Objects.requireNonNull(window, "window");
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public OAuthClient build() {
            if (httpClient == null) {
                httpClient = Http.defaultClient();
            }
            return new OAuthClient(this);
        }
    }
}
