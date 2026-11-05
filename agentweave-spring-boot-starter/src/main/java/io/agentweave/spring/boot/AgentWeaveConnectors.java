package io.agentweave.spring.boot;

import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.tool.ToolConnector;
import io.agentweave.core.tool.ToolRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The client connectors declared in {@code application.yml}. They are started when the
 * application context has refreshed (after the web server, so an application can consume its
 * own endpoints) and registered in the {@link ToolRegistry} under their configured names; they
 * are closed when the context is destroyed, after the web server has stopped.
 *
 * <p>A connector that fails to start fails startup, unless {@code agentweave.fail-fast=false}:
 * then it is closed, left out of the registry and reported {@link State#FAILED}.
 */
public final class AgentWeaveConnectors implements SmartLifecycle, DisposableBean {

    private static final Log log = LogFactory.getLog(AgentWeaveConnectors.class);

    /** Lifecycle state of one connector. */
    public enum State {
        /** Declared but not started yet. */
        PENDING,
        /** Started and registered in the tool registry. */
        UP,
        /** Failed to start (only kept with {@code agentweave.fail-fast=false}). */
        FAILED,
        /** Closed during shutdown. */
        CLOSED
    }

    /** One declared connector and its state. */
    public record Status(String name, ProtocolId protocol, State state, Throwable failure) {
    }

    private final Map<String, ToolConnector> connectors;
    private final ToolRegistry registry;
    private final boolean failFast;
    private final Map<String, Status> status = new LinkedHashMap<>();
    private volatile boolean running;

    AgentWeaveConnectors(Map<String, ToolConnector> connectors, ToolRegistry registry, boolean failFast) {
        this.connectors = new LinkedHashMap<>(connectors);
        this.registry = registry;
        this.failFast = failFast;
        connectors.forEach((name, c) -> status.put(name, new Status(name, c.protocol(), State.PENDING, null)));
    }

    /** Every declared connector's state, in declaration order. */
    public synchronized Map<String, Status> status() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(status));
    }

    /** The connector declared as {@code name}, if any (whatever its state). */
    public Optional<ToolConnector> connector(String name) {
        return Optional.ofNullable(connectors.get(name));
    }

    /**
     * The connector declared as {@code name}, as its concrete type, for protocol-specific calls
     * (for example {@code connector("travel", A2aAgentConnector.class).send(...)}).
     *
     * @throws IllegalArgumentException if there is no such connector or it has another type
     */
    public <T extends ToolConnector> T connector(String name, Class<T> type) {
        ToolConnector c = connectors.get(name);
        if (!type.isInstance(c)) {
            throw new IllegalArgumentException("no " + type.getSimpleName() + " connector named '" + name + "'");
        }
        return type.cast(c);
    }

    /** The compliance report of each connector (negotiated revisions are set once started). */
    public Map<String, ComplianceReport> compliance() {
        Map<String, ComplianceReport> out = new LinkedHashMap<>();
        status().keySet().forEach(name -> out.put(name, connectors.get(name).compliance()));
        return java.util.Collections.unmodifiableMap(out);
    }

    @Override
    public synchronized void start() {
        for (Map.Entry<String, Status> e : new LinkedHashMap<>(status).entrySet()) {
            if (e.getValue().state() != State.PENDING) {
                continue;
            }
            String name = e.getKey();
            ToolConnector connector = connectors.get(name);
            try {
                connector.start();
                registry.register(name, connector);
                status.put(name, new Status(name, connector.protocol(), State.UP, null));
            } catch (RuntimeException failure) {
                closeQuietly(name, connector);
                status.put(name, new Status(name, connector.protocol(), State.FAILED, failure));
                String message = "AgentWeave " + connector.protocol() + " connector '" + name + "' failed to start: "
                        + failure.getMessage();
                if (failFast) {
                    throw new IllegalStateException(message + " (set agentweave.fail-fast=false to start without it)",
                            failure);
                }
                log.warn(message + "; continuing without it (agentweave.fail-fast=false)", failure);
            }
        }
        running = true;
    }

    /** Only marks the connectors stopped: they are closed in {@link #destroy()}, after the web server has drained. */
    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void destroy() {
        running = false;
        new LinkedHashMap<>(status).forEach((name, s) -> {
            if (s.state() == State.UP || s.state() == State.PENDING) {
                registry.unregister(name);
                closeQuietly(name, connectors.get(name));
                status.put(name, new Status(name, s.protocol(), State.CLOSED, null));
            }
        });
    }

    private static void closeQuietly(String name, ToolConnector connector) {
        try {
            Objects.requireNonNull(connector).close();
        } catch (RuntimeException e) {
            log.debug("closing AgentWeave connector '" + name + "' failed", e);
        }
    }
}
