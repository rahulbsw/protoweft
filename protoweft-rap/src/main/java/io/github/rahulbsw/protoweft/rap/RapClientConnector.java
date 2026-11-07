package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Uses the tools of RAP (Reactive Agent Protocol) tool servers through the core
 * {@link ToolConnector} API, as a RAP runtime. Implemented from the specification.
 *
 * <pre>{@code
 * try (RapCallbackListener callbacks = RapCallbackListener.builder().start();
 *      RapClientConnector tools = RapClientConnector.builder("https://tools.example.com")
 *              .callbacks(callbacks).build()) {
 *     tools.start();                                  // GET /.well-known/rap-toolset
 *     ToolOutcome out = tools.invoke(ToolInvocation.of("get_time", Map.of()));
 * }
 * }</pre>
 *
 * <p>An invocation is acknowledged by the tool server and answered later by a callback;
 * {@link #invoke} waits up to the configured timeout (default 10 minutes), then fails with
 * {@link ErrorCategory#TIMEOUT} and tells the tool to cancel, while {@link #invokeAsync} has no
 * timeout. A result with {@code subscription: true} carries a {@link ToolOutcome#subscription()}
 * whose events are the tool's {@code subscription_event}s. Calls run in the connector's default
 * thread ({@code group_id}) unless made through {@link #onThread}. Thread-safe once started.
 */
public final class RapClientConnector implements ToolConnector {

    /** {@link ToolSpec#metadata()} key holding a tool's RAP {@code annotations} object. */
    public static final String META_ANNOTATIONS = "rapAnnotations";

    private static final System.Logger LOG = System.getLogger(RapClientConnector.class.getName());

    private final ClientSettings settings;
    private final ClientHttp http;
    private final ClientRequests requests;
    private final ClientCallbacks callbacks;
    private final List<RapRecoveredSubscription> recoveredSubscriptions;
    private final RecoveredResults recovered;
    private volatile Map<String, Toolset> toolsets;
    private volatile Map<String, String> rejected = Map.of();
    private volatile ComplianceReport report = RapFeatures.report();
    private volatile boolean closed;

    RapClientConnector(ClientSettings settings) {
        this.settings = settings;
        this.http = new ClientHttp(settings.httpClient(), settings.requestTimeout(), settings.credentials(),
                settings.authorizer());
        this.recovered = new RecoveredResults(settings);
        this.requests = new ClientRequests(http, settings, recovered);
        this.callbacks = new ClientCallbacks(settings.storeKey(), settings.store(), settings.router(), requests,
                settings.clock(), settings.dedupRetention());
        this.recoveredSubscriptions = callbacks.recover();
    }

    /** A connector for the RAP tool server at {@code baseUrl} (discovery at {@code <baseUrl>/.well-known/rap-toolset}). */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    @Override
    public ProtocolId protocol() {
        return ProtocolId.RAP;
    }

    /**
     * Fetches and validates every configured server's toolset. A tool defined by two servers is
     * made available from neither (see {@link #rejectedTools()}).
     *
     * @throws ProtoweftException {@code AUTHENTICATION} on 401/403, {@code PROTOCOL} for another
     *                            status or an invalid toolset, {@code CONNECTION} or {@code TIMEOUT}
     */
    @Override
    public synchronized void start() {
        requireOpen();
        if (toolsets != null) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP, "connector already started");
        }
        List<Toolset> loaded = new ArrayList<>();
        for (String server : settings.serverUrls()) {
            loaded.add(requests.fetchToolset(server));
        }
        Set<String> collisions = Toolset.collisions(loaded);
        Map<String, Toolset> byTool = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Toolset t : loaded) {
            for (String name : t.tools().keySet()) {
                if (collisions.contains(name)) {
                    reasons.put(name, "tool '" + name + "' is defined by more than one toolset");
                } else {
                    byTool.put(name, t);
                }
            }
        }
        reasons.values().forEach(r -> LOG.log(System.Logger.Level.WARNING, r + "; it is not available"));
        rejected = Map.copyOf(reasons);
        toolsets = Map.copyOf(byTool);
        report = RapFeatures.report().withNegotiatedRevision(RapFeatures.WIRE_REVISION);
    }

    /** What is implemented; after start the revision spoken (RAP negotiates none) is also set. */
    @Override
    public ComplianceReport compliance() {
        return report;
    }

    /** Tools left out because of a name collision across toolsets, with the reason. */
    public Map<String, String> rejectedTools() {
        return rejected;
    }

    @Override
    public List<ToolSpec> listTools() {
        List<ToolSpec> specs = new ArrayList<>();
        requireStarted().forEach((name, toolset) -> specs.add(toolset.tools().get(name)));
        return List.copyOf(specs);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, CallerIdentity.ANONYMOUS);
    }

    /** Calls a tool; a non-anonymous caller's name is sent as {@code user_id}. */
    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return invokeOn(settings.threadId(), List.of(), invocation, caller);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
        return invoke(invocation, caller);
    }

    /** Calls a tool without a timeout. Cancelling the future tells the tool to cancel (best effort). */
    public CompletableFuture<ToolOutcome> invokeAsync(ToolInvocation invocation) {
        return invokeAsync(invocation, CallerIdentity.ANONYMOUS);
    }

    public CompletableFuture<ToolOutcome> invokeAsync(ToolInvocation invocation, CallerIdentity caller) {
        return dispatch(settings.threadId(), List.of(), invocation, caller).future;
    }

    /** The default thread id ({@code group_id}) of calls made directly on this connector. */
    public String threadId() {
        return settings.threadId();
    }

    /** A view whose calls run in thread {@code threadId}. */
    public RapThread onThread(String threadId) {
        return onThread(threadId, List.of());
    }

    /** A view whose calls run in thread {@code threadId}, a child of {@code ancestors} (root first). */
    public RapThread onThread(String threadId, List<String> ancestors) {
        return new RapThread(this, threadId, ancestors);
    }

    /**
     * Tells every configured tool server that a thread closed ({@code POST /close_thread}),
     * concurrently and once each. The future completes when all attempts finished; it never fails.
     */
    public CompletableFuture<Void> closeThread(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return requests.notifyAll("/close_thread", Map.of("thread_id", threadId), settings.serverUrls());
    }

    /** Tells every configured tool server to cancel a call ({@code POST /cancel_tool_call}), once each. */
    public CompletableFuture<Void> cancelToolCall(String threadId, String toolCallId) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        return requests.notifyAll("/cancel_tool_call", Map.of("thread_id", threadId, "tool_call_id", toolCallId),
                settings.serverUrls());
    }

    /**
     * Receives results that arrive when no caller is waiting (calls sent before a restart, or
     * failed locally for authorization). Results received before a listener is set are held for it.
     */
    public void onRecoveredResult(Consumer<RapRecoveredResult> listener) {
        recovered.listen(Objects.requireNonNull(listener, "listener"));
    }

    /** Subscriptions confirmed before a restart, reattached from the store when this connector was built. */
    public List<RapRecoveredSubscription> recoveredSubscriptions() {
        return recoveredSubscriptions;
    }

    /**
     * Stops receiving callbacks and fails waiting calls with {@code CONNECTION}. Stored calls and
     * subscriptions stay in the {@link RapStore}, so a new connector with the same store resumes them.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        requests.close();
        callbacks.shutdown(new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.RAP, "connector closed"));
        settings.shutdownExecutor();
    }

    ToolOutcome invokeOn(String threadId, List<String> ancestors, ToolInvocation invocation, CallerIdentity caller) {
        Dispatched d = dispatch(threadId, ancestors, invocation, caller);
        try {
            return d.future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandon(d.call);
            throw new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.RAP, "no result from tool '"
                    + invocation.toolName() + "' within " + settings.timeout() + "; cancellation requested", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(d.call);
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP, "interrupted waiting for a result", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ProtoweftException pe) {
                throw pe;
            }
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP, String.valueOf(e.getCause()), e.getCause());
        }
    }

    record Dispatched(ClientCallbacks.PendingCall call, CompletableFuture<ToolOutcome> future) {
    }

    Dispatched dispatch(String threadId, List<String> ancestors, ToolInvocation invocation, CallerIdentity caller) {
        Objects.requireNonNull(invocation, "invocation");
        requireOpen();
        Toolset toolset = requireStarted().get(invocation.toolName());
        if (toolset == null) {
            String reason = rejected.get(invocation.toolName());
            throw new ProtoweftException(ErrorCategory.TOOL_NOT_FOUND, ProtocolId.RAP,
                    reason != null ? reason : "unknown tool: " + invocation.toolName());
        }
        RapCall info = new RapCall(invocation.toolName(), "pw_" + Tokens.newToolCallId(), threadId, toolset.serverUrl());
        String token = Tokens.newCallbackToken();
        String callbackUrl;
        try {
            callbackUrl = settings.router().callbackUrl(token);
        } catch (IllegalStateException e) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP, e.getMessage(), e);
        }
        CompletableFuture<ToolOutcome> future = new CompletableFuture<>();
        ClientCallbacks.PendingCall call = callbacks.open(info, token, future);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", info.operation());
        body.put("arguments", invocation.arguments());
        body.put("id", info.toolCallId());
        body.put("call_id", null);
        body.put("callback_url", callbackUrl);
        body.put("group_id", threadId);
        if (!ancestors.isEmpty()) {
            body.put("thread_ancestors", ancestors);
        }
        if (caller != null && !caller.isAnonymous()) {
            body.put("user_id", caller.name());
        }
        future.whenComplete((r, e) -> {
            if (future.isCancelled()) {
                abandon(call);
            }
        });
        if (!settings.execute(() -> requests.acknowledge(callbacks, call, future, toolset, body))) {
            callbacks.close(call);
            future.completeExceptionally(new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP,
                    "the connector's executor rejected the invocation"));
        }
        return new Dispatched(call, future);
    }

    /** Gives up on a call locally and asks the tool to cancel it (tool-cancellation.md). */
    private void abandon(ClientCallbacks.PendingCall call) {
        callbacks.close(call);
        ClientCallbacks.detach(call);
        requests.cancelRemote(call.info);
    }

    private Map<String, Toolset> requireStarted() {
        Map<String, Toolset> t = toolsets;
        if (t == null) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP, "connector not started");
        }
        return t;
    }

    private void requireOpen() {
        if (closed) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP, "connector closed");
        }
    }

    /** Configures a {@link RapClientConnector}. */
    public static final class Builder {

        private static final RapRetryPolicy DEFAULT_RETRY =
                new RapRetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5));

        private final List<String> serverUrls = new ArrayList<>();
        private CallbackRouter router;
        private Duration timeout = Duration.ofMinutes(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private CredentialProvider credentials = CredentialProvider.NONE;
        private HttpAuthorizer authorizer;
        private RapPromptHandler promptHandler;
        private RapStore store;
        private String storeKey;
        private ExecutorService executor;
        private RapRetryPolicy retry = DEFAULT_RETRY;
        private String threadId;
        private Duration dedupRetention = Duration.ofHours(1);
        private HttpClient httpClient;
        private Consumer<RapRecoveredResult> recoveredListener;
        private Clock clock = Clock.systemUTC();

        private Builder(String baseUrl) {
            addServer(baseUrl);
        }

        /**
         * Another tool server whose toolset this connector also loads. Tool names must be unique
         * across all of them; lifecycle notifications go to every server.
         */
        public Builder addServer(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            try {
                Toolset.httpUrl(trimmed, "baseUrl");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            serverUrls.add(trimmed);
            return this;
        }

        /** Receive callbacks on an endpoint mounted on the application's Protoweft server. */
        public Builder callbacks(RapCallbackEndpoint endpoint) {
            this.router = endpoint.router();
            return this;
        }

        /** Receive callbacks on a built-in listener. */
        public Builder callbacks(RapCallbackListener listener) {
            this.router = listener.router();
            return this;
        }

        /** How long {@link RapClientConnector#invoke} waits for a result; default 10 minutes. */
        public Builder timeout(Duration timeout) {
            this.timeout = positive(timeout, "timeout");
            return this;
        }

        /** Timeout of each HTTP request to a tool server; default 30 seconds. */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = positive(timeout, "requestTimeout");
            return this;
        }

        /**
         * Credentials for tool server requests. Looked up with the server's base URL as target for
         * requests to that origin, else with the request URL. A {@code BearerToken} becomes
         * {@code Authorization: Bearer}; a {@code NamedSecret} a header of that name.
         */
        public Builder credentials(CredentialProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        /** An {@link HttpAuthorizer} (for example OAuth) consulted on every request and on 401/403. */
        public Builder authorizer(HttpAuthorizer authorizer) {
            this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
            return this;
        }

        /**
         * Shows tools' authorization and choice prompts to the user. Without one, authorization
         * requests fail the call with {@link RapAuthorizationRequiredException} and choices are
         * answered with the tool's default, as if the user dismissed them.
         */
        public Builder promptHandler(RapPromptHandler handler) {
            this.promptHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Where pending calls are kept; default in memory. Use {@link FileRapStore} to survive restarts. */
        public Builder store(RapStore store) {
            this.store = Objects.requireNonNull(store, "store");
            return this;
        }

        /** This connector's key in a shared store; default the first base URL. Keep it stable across restarts. */
        public Builder storeKey(String key) {
            this.storeKey = Objects.requireNonNull(key, "key");
            return this;
        }

        /** Runs acknowledgements, prompts and notifications; default a private daemon pool shut down on close. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Retries for invocations and choice answers on 5xx or connection failure; default 3 attempts. */
        public Builder retry(RapRetryPolicy retry) {
            this.retry = Objects.requireNonNull(retry, "retry");
            return this;
        }

        /** The default thread id ({@code group_id}); default a random id per connector. */
        public Builder threadId(String threadId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId");
            return this;
        }

        /** How long processed-callback keys and finished calls are remembered for de-duplication; default 1 hour. */
        public Builder dedupRetention(Duration retention) {
            this.dedupRetention = positive(retention, "dedupRetention");
            return this;
        }

        /** The HTTP client to use; it should not follow redirects. */
        public Builder httpClient(HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        /** See {@link RapClientConnector#onRecoveredResult}; set here to receive results from the start. */
        public Builder onRecoveredResult(Consumer<RapRecoveredResult> listener) {
            this.recoveredListener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Builds the connector and reattaches its stored calls (so their callbacks are accepted
         * from now on); call {@link RapClientConnector#start()} to load the toolsets.
         *
         * @throws ProtoweftException with {@code CONFIGURATION} if no callback hosting is set
         */
        public RapClientConnector build() {
            if (router == null) {
                throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP,
                        "RAP needs a callback endpoint or listener: call callbacks(...)");
            }
            boolean owns = executor == null;
            ExecutorService exec = owns ? ClientSettings.daemonPool("rap-client-") : executor;
            HttpClient client = httpClient != null ? httpClient : HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(requestTimeout).build();
            return new RapClientConnector(new ClientSettings(List.copyOf(serverUrls), router, timeout, requestTimeout,
                    credentials, authorizer, promptHandler, store != null ? store : new InMemoryRapStore(),
                    storeKey != null ? storeKey : serverUrls.get(0), exec, owns, retry,
                    threadId != null ? threadId : "pw-" + Tokens.newToolCallId(), dedupRetention, client,
                    recoveredListener, clock));
        }

        private static Duration positive(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return d;
        }
    }
}
