package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentConnector;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentEvent;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.A2ACardResolver;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TransportProtocol;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An A2A (spec 1.0, JSON-RPC binding) client for one remote agent, built on the official
 * a2a-java SDK. It is both an {@link AgentConnector} (messages and tasks) and a
 * {@link ToolConnector} exposing the agent as a single {@code ask} tool.
 *
 * <pre>{@code
 * try (A2aAgentConnector agent = A2aAgentConnector.builder("https://agent.example.com").build()) {
 *     agent.start();
 *     AgentReply reply = agent.send(AgentMessage.user("hello"));
 * }
 * }</pre>
 *
 * <p>Thread-safe once started. See {@link #compliance()} for exactly what is implemented.
 */
public final class A2aAgentConnector implements AgentConnector, ToolConnector {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final String baseUrl;
    private final CredentialProvider credentials;
    private final ObservationHandler observations;
    private final Duration timeout;
    private final boolean preferStreaming;
    private final List<String> acceptedOutputModes;
    private final ComplianceReport baseReport;
    private final ExecutorService calls;

    private volatile AgentCard card;
    private volatile Client blocking;
    private volatile Client streaming;
    private volatile AgentDescriptor descriptor;
    private volatile ComplianceReport report;

    private A2aAgentConnector(Builder b) {
        this.baseUrl = b.baseUrl;
        this.credentials = b.credentials;
        this.observations = b.observations;
        this.timeout = b.timeout;
        this.preferStreaming = b.streaming;
        this.acceptedOutputModes = List.copyOf(b.acceptedOutputModes);
        // The wire revision is the one the SDK's JSON-RPC transport sends in A2A-Version.
        this.baseReport = new ComplianceReport(ProtocolId.A2A, SdkInfo.CLIENT_NAME, SdkInfo.VERSION,
                List.of(AgentInterface.CURRENT_PROTOCOL_VERSION), null,
                A2aFeatures.CLIENT_SUPPORTED, A2aFeatures.CLIENT_UNSUPPORTED);
        this.report = baseReport;
        this.calls = Executors.newCachedThreadPool(SdkInfo.daemonThreads("protoweft-a2a-client-"));
    }

    /** Starts building a connector for the agent whose card is at {@code <agentBaseUrl>/.well-known/agent-card.json}. */
    public static Builder builder(String agentBaseUrl) {
        return new Builder(agentBaseUrl);
    }

    @Override
    public ProtocolId protocol() {
        return ProtocolId.A2A;
    }

    /**
     * Fetches the agent card and prepares the JSON-RPC client.
     *
     * @throws ProtoweftException {@code CONNECTION}/{@code AUTHENTICATION} if the card cannot be
     *         fetched, {@code UNSUPPORTED} if the agent has no JSON-RPC interface,
     *         {@code VERSION_MISMATCH} if that interface is not A2A 1.x
     */
    @Override
    public synchronized void start() {
        if (card != null) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.A2A, "connector already started");
        }
        A2AHttpClient http = new JdkA2AHttpClient(HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build());
        AgentCard fetched = observe("agent-card", baseUrl, true, () -> A2ACardResolver.builder()
                .httpClient(http).baseUrl(baseUrl)
                .authHeaders(CredentialHeaders.resolve(credentials, baseUrl)).build().getAgentCard());
        AgentInterface jsonRpc = fetched.supportedInterfaces().stream()
                .filter(i -> TransportProtocol.JSONRPC.asString().equals(i.protocolBinding()))
                .findFirst()
                .orElseThrow(() -> new ProtoweftException(ErrorCategory.UNSUPPORTED, ProtocolId.A2A,
                        "agent at " + baseUrl + " offers no JSON-RPC interface"));
        if (!sameMajor(jsonRpc.protocolVersion(), AgentInterface.CURRENT_PROTOCOL_VERSION)) {
            throw new ProtoweftException(ErrorCategory.VERSION_MISMATCH, ProtocolId.A2A,
                    "agent's JSON-RPC interface speaks A2A " + jsonRpc.protocolVersion() + "; this connector speaks "
                            + AgentInterface.CURRENT_PROTOCOL_VERSION);
        }
        this.blocking = observe("agent-card", baseUrl, true, () -> newClient(fetched, http, false));
        this.streaming = observe("agent-card", baseUrl, true, () -> newClient(fetched, http, true));
        this.descriptor = A2aMapping.toDescriptor(fetched);
        this.report = baseReport.withNegotiatedRevision(jsonRpc.protocolVersion());
        this.card = fetched;
    }

    @Override
    public ComplianceReport compliance() {
        return report;
    }

    @Override
    public AgentDescriptor descriptor() {
        requireStarted();
        return descriptor;
    }

    /** Whether {@link #send(AgentMessage, Consumer)} streams: the builder allows it and the agent's card declares it. */
    public boolean streams() {
        return preferStreaming && requireStarted().capabilities().streaming();
    }

    @Override
    public AgentReply send(AgentMessage message) {
        Objects.requireNonNull(message, "message");
        requireStarted();
        Message request = toA2a(message);
        return observe("SendMessage", taskTarget(message), false, () -> {
            long deadline = System.nanoTime() + timeout.toNanos();
            AtomicReference<ClientEvent> result = new AtomicReference<>();
            call("SendMessage", deadline, () -> {
                blocking.sendMessage(request, List.of((event, c) -> result.set(event)), null, null);
                return null;
            });
            ClientEvent event = result.get();
            if (event instanceof MessageEvent m) {
                return A2aMapping.toReply(m.getMessage());
            }
            if (!(event instanceof TaskEvent t)) {
                throw new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.A2A, "SendMessage returned no result");
            }
            // The server answers a blocking send once its agent returns; keep polling until the
            // task is terminal or waiting on the caller.
            Task task = t.getTask();
            while (!StreamCollector.settled(task)) {
                sleepUntil(deadline);
                String id = task.id();
                task = call("GetTask", deadline, () -> blocking.getTask(new TaskQueryParams(id)));
            }
            return A2aMapping.toReply(task);
        });
    }

    /**
     * Streams updates while the agent works, when {@link #streams()}; otherwise behaves like
     * {@link #send(AgentMessage)} and delivers no events. Exceptions thrown by {@code events}
     * are ignored.
     */
    @Override
    public AgentReply send(AgentMessage message, Consumer<AgentEvent> events) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(events, "events");
        if (!streams()) {
            return send(message);
        }
        Message request = toA2a(message);
        return observe("SendStreamingMessage", taskTarget(message), false, () -> stream("SendStreamingMessage",
                events, (consumer, onEnd) -> streaming.sendMessage(request, List.of(consumer), onEnd, null)));
    }

    /**
     * Re-attaches to a running task's event stream ({@code SubscribeToTask}) and waits until it
     * is terminal or interrupted.
     *
     * @throws ProtoweftException {@code UNSUPPORTED} if streaming is off or the agent doesn't stream
     */
    public AgentReply resubscribe(String taskId, Consumer<AgentEvent> events) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(events, "events");
        if (!streams()) {
            throw new ProtoweftException(ErrorCategory.UNSUPPORTED, ProtocolId.A2A,
                    "resubscribe needs streaming, which is disabled or not offered by the agent");
        }
        return observe("SubscribeToTask", taskId, false, () -> stream("SubscribeToTask", events,
                (consumer, onEnd) -> streaming.subscribeToTask(new TaskIdParams(taskId), List.of(consumer), onEnd, null)));
    }

    @Override
    public AgentReply getTask(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        requireStarted();
        return observe("GetTask", taskId, false, () -> A2aMapping.toReply(
                call("GetTask", deadline(), () -> blocking.getTask(new TaskQueryParams(taskId)))));
    }

    @Override
    public AgentReply cancel(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        requireStarted();
        return observe("CancelTask", taskId, false, () -> A2aMapping.toReply(
                call("CancelTask", deadline(), () -> blocking.cancelTask(new CancelTaskParams(taskId)))));
    }

    /**
     * Fetches the extended agent card ({@code GetExtendedAgentCard}), which agents serve only to
     * authenticated callers.
     *
     * @throws ProtoweftException {@code UNSUPPORTED} if the public card doesn't declare one,
     *         {@code AUTHENTICATION} if the agent refuses this caller
     */
    public AgentDescriptor extendedDescriptor() {
        if (!requireStarted().capabilities().extendedAgentCard()) {
            throw new ProtoweftException(ErrorCategory.UNSUPPORTED, ProtocolId.A2A,
                    "the agent's card does not declare an extended agent card");
        }
        return observe("GetExtendedAgentCard", "", false, () -> A2aMapping.toDescriptor(
                call("GetExtendedAgentCard", deadline(), () -> blocking.getExtendedAgentCard((String) null))));
    }

    /** Exactly one tool, {@code ask}, described from the agent card. */
    @Override
    public List<ToolSpec> listTools() {
        return List.of(AskTool.spec(descriptor()));
    }

    /**
     * Calls {@code ask}: sends {@code message} (continuing {@code task_id}/{@code context_id}
     * if given). An interrupted task yields its question as text and
     * {@code {task_id, context_id, state}} as structured content; failed, rejected and
     * canceled tasks are outcomes with {@code error} set.
     */
    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        requireStarted();
        return AskTool.outcome(send(AskTool.message(invocation)));
    }

    @Override
    public synchronized void close() {
        Client b = blocking;
        Client s = streaming;
        blocking = null;
        streaming = null;
        if (b != null) {
            b.close();
        }
        if (s != null) {
            s.close();
        }
        calls.shutdownNow();
    }

    // ---- internals ---------------------------------------------------------------------

    private Client newClient(AgentCard agentCard, A2AHttpClient http, boolean stream) {
        ClientConfig config = ClientConfig.builder()
                .setStreaming(stream)
                .setAcceptedOutputModes(acceptedOutputModes)
                .build();
        return Client.builder(agentCard)
                .clientConfig(config)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder()
                        .httpClient(http)
                        .addInterceptor(new CredentialHeaders(credentials, baseUrl)))
                .build();
    }

    /** Runs one streaming call and waits for the task to be terminal or interrupted. */
    private AgentReply stream(String operation, Consumer<AgentEvent> events, StreamCall start) {
        long deadline = System.nanoTime() + timeout.toNanos();
        StreamCollector collector = new StreamCollector(operation, events);
        call(operation, deadline, () -> {
            start.begin(collector.consumer(), collector.onEnd());
            return null;
        });
        return await(operation, deadline, collector.done);
    }

    private <T> T call(String operation, long deadline, java.util.concurrent.Callable<T> action) {
        return await(operation, deadline, CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, calls));
    }

    private <T> T await(String operation, long deadline, CompletableFuture<T> future) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.A2A,
                    "A2A " + operation + " did not finish within " + timeout, e);
        } catch (ExecutionException e) {
            throw A2aErrors.translate(e.getCause() == null ? e : e.getCause(), operation, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.A2A, "interrupted during " + operation, e);
        }
    }

    private void sleepUntil(long deadline) {
        long pause = Math.min(POLL_INTERVAL.toNanos(), deadline - System.nanoTime());
        if (pause <= 0) {
            throw new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.A2A,
                    "A2A task did not finish within " + timeout);
        }
        try {
            TimeUnit.NANOSECONDS.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.A2A, "interrupted while waiting for task", e);
        }
    }

    private long deadline() {
        return System.nanoTime() + timeout.toNanos();
    }

    private static Message toA2a(AgentMessage message) {
        try {
            return A2aMapping.toA2a(message);
        } catch (IllegalArgumentException e) {
            throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.A2A, e.getMessage(), e);
        }
    }

    private static String taskTarget(AgentMessage message) {
        return message.taskId() == null ? "" : message.taskId();
    }

    private static boolean sameMajor(String a, String b) {
        return a != null && a.split("\\.")[0].equals(b.split("\\.")[0]);
    }

    private AgentCard requireStarted() {
        AgentCard c = card;
        if (c == null || blocking == null) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.A2A,
                    "connector not started; call start() first");
        }
        return c;
    }

    private <T> T observe(String operation, String target, boolean connecting, Supplier<T> action) {
        return ObservationHandler.observe(observations, new Observation(ProtocolId.A2A, operation, target), () -> {
            try {
                return action.get();
            } catch (RuntimeException e) {
                throw A2aErrors.translate(e, operation, connecting);
            }
        });
    }

    @FunctionalInterface
    private interface StreamCall {
        void begin(BiConsumer<ClientEvent, AgentCard> consumer, Consumer<Throwable> onEnd) throws Exception;
    }

    /** Configures an {@link A2aAgentConnector}. */
    public static final class Builder {

        private final String baseUrl;
        private CredentialProvider credentials = CredentialProvider.NONE;
        private ObservationHandler observations = ObservationHandler.NOOP;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean streaming = true;
        private List<String> acceptedOutputModes = List.of();

        private Builder(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "agentBaseUrl");
        }

        /**
         * Credentials for the card fetch and every call: {@code BearerToken} becomes
         * {@code Authorization: Bearer …}, {@code NamedSecret} becomes a header {@code name: value}.
         */
        public Builder credentials(CredentialProvider provider) {
            this.credentials = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public Builder observations(ObservationHandler handler) {
            this.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Connect timeout, and the longest a call waits for a terminal or interrupted task. Default 60 seconds. */
        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** Whether {@link #send(AgentMessage, Consumer)} may stream when the agent supports it. Default {@code true}. */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /** Media types this client accepts in replies ({@code acceptedOutputModes}); default none (agent decides). */
        public Builder acceptedOutputModes(List<String> modes) {
            this.acceptedOutputModes = List.copyOf(modes);
            return this;
        }

        public A2aAgentConnector build() {
            return new A2aAgentConnector(this);
        }
    }
}
