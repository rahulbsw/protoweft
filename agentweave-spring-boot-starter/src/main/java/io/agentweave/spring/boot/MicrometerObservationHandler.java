package io.agentweave.spring.boot;

import io.agentweave.core.observe.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records every AgentWeave operation as a Micrometer observation named {@value #NAME}, with
 * low-cardinality keys {@value #PROTOCOL} and {@value #OPERATION} and the high-cardinality key
 * {@value #TARGET}; the contextual name is {@code "<protocol> <operation>"}.
 *
 * <p>Start and stop may run on different threads (the MCP server is reactive), so in-flight
 * observations are keyed by the identity of the AgentWeave observation, weakly: an operation
 * abandoned without a stop (for example a cancelled call) is dropped, not leaked.
 */
public final class MicrometerObservationHandler implements ObservationHandler {

    /** The Micrometer observation name. */
    public static final String NAME = "agentweave.operation";
    /** Low-cardinality key: the protocol, for example {@code mcp}. */
    public static final String PROTOCOL = "agentweave.protocol";
    /** Low-cardinality key: the operation, for example {@code tools/call}. */
    public static final String OPERATION = "agentweave.operation";
    /** High-cardinality key: what the operation acts on, for example a tool name. */
    public static final String TARGET = "agentweave.target";

    private final ObservationRegistry registry;
    private final Map<Key, io.micrometer.observation.Observation> inFlight = new ConcurrentHashMap<>();
    private final ReferenceQueue<Observation> collected = new ReferenceQueue<>();

    public MicrometerObservationHandler(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onStart(Observation observation) {
        expunge();
        io.micrometer.observation.Observation micrometer = io.micrometer.observation.Observation
                .createNotStarted(NAME, registry)
                .contextualName(observation.protocol().value() + " " + observation.operation())
                .lowCardinalityKeyValue(PROTOCOL, observation.protocol().value())
                .lowCardinalityKeyValue(OPERATION, observation.operation())
                .highCardinalityKeyValue(TARGET, observation.target())
                .start();
        inFlight.put(new Key(observation, collected), micrometer);
    }

    @Override
    public void onSuccess(Observation observation, Duration elapsed) {
        io.micrometer.observation.Observation micrometer = inFlight.remove(new Key(observation, null));
        if (micrometer != null) {
            micrometer.stop();
        }
    }

    @Override
    public void onError(Observation observation, Duration elapsed, Throwable error) {
        io.micrometer.observation.Observation micrometer = inFlight.remove(new Key(observation, null));
        if (micrometer != null) {
            micrometer.error(error);
            micrometer.stop();
        }
    }

    private void expunge() {
        for (Reference<? extends Observation> ref; (ref = collected.poll()) != null; ) {
            inFlight.remove(ref);
        }
    }

    /** A weak identity key: two keys are equal when they refer to the same observation instance. */
    private static final class Key extends WeakReference<Observation> {
        private final int hash;

        Key(Observation observation, ReferenceQueue<Observation> queue) {
            super(observation, queue);
            this.hash = System.identityHashCode(observation);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key k && k.hash == hash && k.get() != null && k.get() == get();
        }
    }
}
