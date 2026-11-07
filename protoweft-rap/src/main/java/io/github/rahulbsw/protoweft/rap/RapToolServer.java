package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Serves any {@link ToolSource} as a RAP tool server, for RAP runtimes such as Hydro Infinity
 * agents. Implemented from the specification.
 *
 * <pre>{@code
 * RapToolServer rap = RapToolServer.builder(tools).name("weather").build();
 * ProtoweftServer.builder().endpoints(rap).authenticator(myAuthenticator).start();
 * }</pre>
 *
 * <p>Routes, under the optional base path: {@code GET /.well-known/rap-toolset},
 * {@code POST /invoke} (acknowledged with 200, then run on the executor; the result is posted to
 * the invocation's {@code callback_url}), {@code POST /close_thread}, {@code POST /cancel_tool_call}
 * and {@code POST /user_choice_response}. All of them sit behind the host's
 * {@code InboundAuthFilter}, so the authenticator that protects invocations also protects the
 * lifecycle routes. Tools reach RAP features through {@link RapCallContext}.
 */
public final class RapToolServer implements ServerEndpointProvider, AutoCloseable {

    /** {@code CallerIdentity} attribute holding the invocation's {@code user_id}, when it sent one. */
    public static final String USER_ID_ATTRIBUTE = "rap.userId";

    private final ServerState state;
    private final List<RapSubscription> recovered;

    private RapToolServer(ServerState state) {
        this.state = state;
        this.recovered = state.recover();
    }

    public static Builder builder(ToolSource tools) {
        return new Builder(tools);
    }

    @Override
    public List<ServletEndpoint> endpoints() {
        List<String> patterns = RapServlet.ROUTES.stream().map(r -> state.basePath + r).toList();
        String name = state.basePath.isEmpty() ? "rap" : "rap:" + state.basePath;
        return List.of(new ServletEndpoint(name, ProtocolId.RAP, patterns, new RapServlet(state), false));
    }

    /** Server-side coverage, with the same feature ids as the client. */
    public ComplianceReport compliance() {
        return RapFeatures.report();
    }

    /**
     * Subscriptions confirmed before a restart and reattached from the {@link RapStore} at build
     * time. The application resumes them by emitting events; one that is never resumed ends when
     * the runtime cancels it or closes its thread.
     */
    public List<RapSubscription> recoveredSubscriptions() {
        return recovered;
    }

    /** Stops the server's executors. Stored subscriptions remain for the next instance. */
    @Override
    public void close() {
        state.shutdown();
    }

    /** Configures a {@link RapToolServer}. */
    public static final class Builder {

        private static final RapRetryPolicy DEFAULT_RETRY =
                new RapRetryPolicy(5, Duration.ofMillis(500), Duration.ofSeconds(30));

        private final ToolSource tools;
        private String name = "protoweft";
        private String description;
        private Supplier<String> baseUrl;
        private String basePath = "";
        private RapStore store;
        private String storeKey;
        private ExecutorService executor;
        private RapRetryPolicy callbackRetry = DEFAULT_RETRY;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private RapCallbackPolicy callbackPolicy = RapCallbackPolicy.DEFAULT;
        private HttpAuthorizer callbackAuthorizer;
        private Duration choiceTimeout = Duration.ofMinutes(10);
        private HttpClient httpClient;
        private Clock clock = Clock.systemUTC();

        private Builder(ToolSource tools) {
            this.tools = Objects.requireNonNull(tools, "tools");
        }

        /** The toolset {@code name}, 1 to 128 characters; default {@code protoweft}. */
        public Builder name(String name) {
            if (name == null || name.isEmpty() || name.length() > 128) {
                throw new IllegalArgumentException("toolset name must be 1 to 128 characters");
            }
            this.name = name;
            return this;
        }

        /** The toolset {@code description}; default none. */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /**
         * The public URL runtimes use as this server's base (where {@code /.well-known/rap-toolset}
         * lives); used for the manifest {@code endpoint} and choice {@code response_url}s. Default:
         * derived from each request.
         */
        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            return baseUrl(() -> baseUrl);
        }

        public Builder baseUrl(Supplier<String> baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** Mount every route under this path, for example {@code /rap}; default the root. */
        public Builder basePath(String path) {
            Objects.requireNonNull(path, "path");
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            if (!p.isEmpty() && !p.startsWith("/")) {
                throw new IllegalArgumentException("basePath must start with '/': " + path);
            }
            this.basePath = p;
            return this;
        }

        /** Where active subscriptions are kept; default in memory. Use {@link FileRapStore} to survive restarts. */
        public Builder store(RapStore store) {
            this.store = Objects.requireNonNull(store, "store");
            return this;
        }

        /** This server's key in a shared store; default the toolset name. Keep it stable across restarts. */
        public Builder storeKey(String key) {
            this.storeKey = Objects.requireNonNull(key, "key");
            return this;
        }

        /** Runs tool calls; default a private daemon pool shut down by {@link RapToolServer#close()}. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Retries for callbacks on 5xx, connection failures and timeouts; default 5 attempts, 0.5 s doubling to 30 s. */
        public Builder callbackRetry(RapRetryPolicy retry) {
            this.callbackRetry = Objects.requireNonNull(retry, "retry");
            return this;
        }

        /** Timeout of each callback request; default 30 seconds. */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Which callback URLs are accepted; default {@link RapCallbackPolicy#DEFAULT}. */
        public Builder callbackPolicy(RapCallbackPolicy policy) {
            this.callbackPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Supplies an {@code Authorization} header for callbacks, for runtimes that require one. */
        public Builder callbackAuthorizer(HttpAuthorizer authorizer) {
            this.callbackAuthorizer = Objects.requireNonNull(authorizer, "authorizer");
            return this;
        }

        /** How long {@link RapCallContext#askUser(String, List, int)} waits before using the default; default 10 minutes. */
        public Builder choiceTimeout(Duration timeout) {
            this.choiceTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** The HTTP client for callbacks; it should not follow redirects. */
        public Builder httpClient(HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Builds the server and reattaches its stored subscriptions. */
        public RapToolServer build() {
            boolean owns = executor == null;
            ExecutorService exec = owns ? ClientSettings.daemonPool("rap-tool-") : executor;
            ExecutorService deliveries = ClientSettings.daemonPool("rap-callback-out-");
            HttpClient client = httpClient != null ? httpClient : HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(requestTimeout).build();
            CallbackSender sender = new CallbackSender(client, requestTimeout, callbackRetry, callbackAuthorizer,
                    deliveries);
            return new RapToolServer(new ServerState(tools, name, description, baseUrl, basePath,
                    store != null ? store : new InMemoryRapStore(), storeKey != null ? storeKey : name, exec, owns,
                    deliveries, sender, callbackPolicy, choiceTimeout, clock));
        }
    }
}
