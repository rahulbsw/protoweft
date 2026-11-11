package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves one agent over AG-UI's HTTP + SSE binding.
 *
 * <pre>{@code
 * AgUiAgentServer agui = AgUiAgentServer.builder((input, run) -> {
 *             run.say("Hello from Java");
 *             run.finish();
 *         })
 *         .allowedOrigins("https://app.example.com")
 *         .build();
 * ProtoweftServer.builder().port(8080).endpoints(agui).start();   // POST /agui
 * }</pre>
 *
 * <p>Any core {@link AgentHandler} (for example one already served over A2A) is served with
 * {@code builder(AgUi.agent(handler))}. Endpoints: {@code POST <path>} runs the agent;
 * {@code GET <path>/capabilities} returns its capabilities document; {@code OPTIONS} answers CORS
 * preflights. Authentication is the host's {@code InboundAuthenticator}.
 */
public final class AgUiAgentServer implements ServerEndpointProvider, AutoCloseable {

    /** Appended to the agent path for the capabilities document. */
    public static final String CAPABILITIES_SUFFIX = "/capabilities";

    /** The capability groups of the spec's {@code AgentCapabilities}. */
    static final Set<String> CAPABILITY_GROUPS = Set.of("identity", "transport", "tools", "output", "state",
            "multiAgent", "reasoning", "multimodal", "execution", "humanInTheLoop", "custom");

    private final ServletEndpoint endpoint;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    private AgUiAgentServer(Builder b) {
        this.ownsExecutor = b.executor == null;
        this.executor = ownsExecutor ? Executors.newCachedThreadPool(daemonThreads()) : b.executor;
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("transport", Map.of("streaming", true, "httpBinary", false));
        capabilities.putAll(b.capabilities);
        AgUiServlet servlet = new AgUiServlet(b.agent, executor, b.observations, new Cors(b.allowedOrigins),
                b.maxRequestBytes, capabilities);
        this.endpoint = new ServletEndpoint(b.endpointName, ProtocolId.AGUI,
                List.of(b.path, b.path + CAPABILITIES_SUFFIX), servlet, true);
    }

    /** Serves a native AG-UI agent. */
    public static Builder builder(AgUiAgent agent) {
        return new Builder(Objects.requireNonNull(agent, "agent"));
    }

    @Override
    public List<ServletEndpoint> endpoints() {
        return List.of(endpoint);
    }

    public ComplianceReport compliance() {
        return AgUiFeatures.serverReport();
    }

    /** Stops the worker pool if the server created it; runs in progress are interrupted. */
    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static java.util.concurrent.ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "protoweft-agui-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public static final class Builder {
        private final AgUiAgent agent;
        private String path = "/agui";
        private String endpointName = "agui";
        private List<String> allowedOrigins = List.of();
        private Map<String, Object> capabilities = Map.of();
        private ObservationHandler observations = ObservationHandler.NOOP;
        private ExecutorService executor;
        private int maxRequestBytes = 4 * 1024 * 1024;

        private Builder(AgUiAgent agent) {
            this.agent = agent;
        }

        /** The agent's URL path; default {@code /agui}. */
        public Builder path(String path) {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/") || path.endsWith("/") || path.contains("*")) {
                throw new IllegalArgumentException("path must start with '/', not end with '/', and have no wildcard");
            }
            this.path = path;
            return this;
        }

        /** Name of the servlet endpoint; must be unique per host. Default {@code agui}. */
        public Builder endpointName(String name) {
            this.endpointName = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Browser origins allowed to call the agent cross-origin, for example
         * {@code https://app.example.com}; {@code "*"} allows any. Default none: no CORS.
         */
        public Builder allowedOrigins(String... origins) {
            this.allowedOrigins = List.of(origins);
            return this;
        }

        /**
         * The agent's capabilities document (the spec's {@code AgentCapabilities}): groups such as
         * {@code identity}, {@code tools}, {@code state}, {@code reasoning}, {@code humanInTheLoop}.
         * Declare only what the agent does; {@code transport} defaults to SSE streaming.
         */
        public Builder capabilities(Map<String, Object> capabilities) {
            for (String group : capabilities.keySet()) {
                if (!CAPABILITY_GROUPS.contains(group)) {
                    throw new IllegalArgumentException("unknown capability group '" + group + "'; expected one of "
                            + CAPABILITY_GROUPS);
                }
                if (!(capabilities.get(group) instanceof Map)) {
                    throw new IllegalArgumentException("capability group '" + group + "' must be an object");
                }
            }
            this.capabilities = Map.copyOf(capabilities);
            return this;
        }

        public Builder observations(ObservationHandler handler) {
            this.observations = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Runs agents; default a cached daemon pool owned (and shut down) by the server. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Largest accepted run input; larger requests get 413. Default 4 MiB. */
        public Builder maxRequestBytes(int bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("maxRequestBytes must be positive");
            }
            this.maxRequestBytes = bytes;
            return this;
        }

        public AgUiAgentServer build() {
            return new AgUiAgentServer(this);
        }
    }
}
