package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import org.a2aproject.sdk.spec.AgentInterface;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Serves an Protoweft {@link AgentHandler} as an A2A agent (spec 1.0, JSON-RPC binding),
 * built on a2a-java's server SDK: the public agent card at
 * {@code GET /.well-known/agent-card.json} and JSON-RPC (with SSE streaming) at
 * {@code POST <path>}. Mount it in any Protoweft host:
 *
 * <pre>{@code
 * A2aAgentServer agent = A2aAgentServer.builder(handler, descriptor)
 *         .publicUrl("https://agents.example.com")
 *         .build();
 * ProtoweftServer.builder().port(8080).endpoints(agent).start();
 * }</pre>
 *
 * <p>A task belongs to the caller (as named by the host's {@code InboundAuthenticator}) that
 * created it; other callers get "task not found". State is kept in memory. Call
 * {@link #close()} to stop the background threads.
 */
public final class A2aAgentServer implements ServerEndpointProvider, AutoCloseable {

    /** The well-known path of the public agent card. */
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    private final SdkServer sdk;
    private final List<ServletEndpoint> endpoints;
    private final ComplianceReport report;

    private A2aAgentServer(Builder b) {
        TaskOwnership ownership = new TaskOwnership();
        HandlerExecutor executor = new HandlerExecutor(b.handler, ownership, b.observations);
        this.sdk = new SdkServer(b, executor, ownership);
        this.endpoints = List.of(
                new ServletEndpoint(b.endpointName + "-card", ProtocolId.A2A, List.of(AGENT_CARD_PATH),
                        new AgentCardServlet(sdk), true),
                new ServletEndpoint(b.endpointName, ProtocolId.A2A, List.of(b.path),
                        new JsonRpcServlet(sdk, b.observations, b.extended != null), true));
        Set<String> supported = new HashSet<>(A2aFeatures.SERVER_ALWAYS);
        Set<String> unsupported = new HashSet<>(A2aFeatures.SERVER_NEVER);
        (b.streaming ? supported : unsupported).addAll(List.of(A2aFeatures.SEND_STREAMING_MESSAGE,
                A2aFeatures.SUBSCRIBE_TO_TASK));
        (b.push ? supported : unsupported).add(A2aFeatures.PUSH_NOTIFICATIONS);
        (b.extended != null ? supported : unsupported).add(A2aFeatures.DISCOVERY_EXTENDED_AGENT_CARD);
        // A server does not negotiate per peer, so the negotiated revision stays empty.
        this.report = new ComplianceReport(ProtocolId.A2A, SdkInfo.SERVER_NAME, SdkInfo.VERSION,
                List.of(AgentInterface.CURRENT_PROTOCOL_VERSION), null, supported, unsupported);
    }

    /**
     * @param handler    the agent implementation
     * @param descriptor name, description, version and skills published in the agent card
     */
    public static Builder builder(AgentHandler handler, AgentDescriptor descriptor) {
        return new Builder(handler, descriptor);
    }

    /** The agent-card endpoint and the JSON-RPC endpoint, both async. */
    @Override
    public List<ServletEndpoint> endpoints() {
        return endpoints;
    }

    /** What this server implements, given its configuration. */
    public ComplianceReport compliance() {
        return report;
    }

    /** The JSON-RPC URL advertised in the agent card ({@code <publicUrl><path>}). */
    public String interfaceUrl() {
        return sdk.publicCard().supportedInterfaces().get(0).url();
    }

    /** Stops the event-bus thread and the executors this server created. Safe to call more than once. */
    @Override
    public void close() {
        sdk.close();
    }

    /** Configures an {@link A2aAgentServer}. */
    public static final class Builder {

        final AgentHandler handler;
        final AgentDescriptor descriptor;
        Supplier<String> publicUrl;
        String path = "/a2a";
        String endpointName = "a2a";
        boolean streaming = true;
        boolean push = true;
        boolean insecurePushTargets;
        AgentDescriptor extended;
        List<String> inputModes = List.of("text/plain", "application/json");
        List<String> outputModes = List.of("text/plain", "application/json");
        ObservationHandler observations = ObservationHandler.NOOP;
        Duration agentTimeout = Duration.ofSeconds(30);
        ExecutorService agentExecutor;

        private Builder(AgentHandler handler, AgentDescriptor descriptor) {
            this.handler = Objects.requireNonNull(handler, "handler");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        /** Externally reachable base URL (scheme, host, port, context path); required. */
        public Builder publicUrl(String url) {
            Objects.requireNonNull(url, "url");
            this.publicUrl = () -> url;
            return this;
        }

        /**
         * Base URL resolved when the card is first served, for hosts whose port is known only
         * after start (for example port 0).
         */
        public Builder publicUrl(Supplier<String> url) {
            this.publicUrl = Objects.requireNonNull(url, "url");
            return this;
        }

        /** Servlet path of the JSON-RPC endpoint; default {@code /a2a}. */
        public Builder path(String path) {
            if (path == null || !path.startsWith("/") || path.contains("*")) {
                throw new IllegalArgumentException("path must be an exact servlet path starting with '/'");
            }
            this.path = path;
            return this;
        }

        /** Endpoint name prefix, unique per host; default {@code a2a}. */
        public Builder endpointName(String name) {
            this.endpointName = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Whether to offer {@code SendStreamingMessage} / {@code SubscribeToTask}; default on. */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /** Whether to offer push notifications; default on. */
        public Builder pushNotifications(boolean push) {
            this.push = push;
            return this;
        }

        /**
         * Allows push webhooks over plain {@code http} and to loopback/private addresses. For
         * development and tests only; the default accepts only {@code https} public targets.
         */
        public Builder insecurePushTargets(boolean allow) {
            this.insecurePushTargets = allow;
            return this;
        }

        /** A richer card served by {@code GetExtendedAgentCard} to authenticated (non-anonymous) callers only. */
        public Builder extendedDescriptor(AgentDescriptor extended) {
            this.extended = Objects.requireNonNull(extended, "extended");
            return this;
        }

        /** {@code defaultInputModes}; default {@code text/plain, application/json}. */
        public Builder inputModes(List<String> modes) {
            this.inputModes = List.copyOf(modes);
            return this;
        }

        /** {@code defaultOutputModes}; default {@code text/plain, application/json}. */
        public Builder outputModes(List<String> modes) {
            this.outputModes = List.copyOf(modes);
            return this;
        }

        public Builder observations(ObservationHandler handler) {
            this.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * How long a blocking {@code SendMessage} waits for the handler before answering with
         * the task's current state; default 30 seconds.
         */
        public Builder agentTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.toSeconds() < 1) {
                throw new IllegalArgumentException("agent timeout must be at least one second");
            }
            this.agentTimeout = timeout;
            return this;
        }

        /** Runs agent handlers; default a cached daemon pool owned (and shut down) by the server. */
        public Builder executor(ExecutorService executor) {
            this.agentExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** @throws ProtoweftException {@code CONFIGURATION} if no public URL was set */
        public A2aAgentServer build() {
            if (publicUrl == null) {
                throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.A2A,
                        "publicUrl is required: the agent card must advertise its endpoint URL");
            }
            return new A2aAgentServer(this);
        }
    }
}
