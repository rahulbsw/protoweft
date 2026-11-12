package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;

import java.util.List;
import java.util.Set;

/** Feature ids reported in this module's {@link ComplianceReport}s. */
public final class AgUiFeatures {

    /** HTTP + Server-Sent Events, the required binding. */
    public static final String BINDING_SSE = "binding/http-sse";
    /** HTTP + Protobuf, the optional binding: supported when protobuf-java is on the classpath. */
    public static final String BINDING_PROTOBUF = "binding/http-protobuf";
    public static final String RUNS = "events/runs-and-steps";
    public static final String TEXT = "events/text-messages";
    public static final String TOOL_CALLS = "events/tool-calls";
    public static final String REASONING = "events/reasoning";
    public static final String STATE = "events/state";
    public static final String ACTIVITY = "events/activity";
    public static final String SUBAGENTS = "events/subagents";
    public static final String PASSTHROUGH = "events/passthrough";
    public static final String INTERRUPTS = "interrupt-resume";
    /** The application's own tools offered on run input and called by the agent. */
    public static final String CLIENT_TOOLS = "frontend-tools";
    public static final String VERSION_NEGOTIATION = "version-negotiation";
    /**
     * {@code GET <path>/capabilities}: a Protoweft convention; the spec defines the capabilities
     * document but no way to retrieve it.
     */
    public static final String CAPABILITIES_ENDPOINT = "protoweft/capabilities-endpoint";
    public static final String CORS = "protoweft/cors";

    static final Set<String> SERVER_SUPPORTED = Set.of(BINDING_SSE, RUNS, TEXT, TOOL_CALLS, REASONING, STATE,
            ACTIVITY, SUBAGENTS, PASSTHROUGH, INTERRUPTS, CLIENT_TOOLS, VERSION_NEGOTIATION, CAPABILITIES_ENDPOINT,
            CORS);

    private AgUiFeatures() {
    }

    /** The client runs the application's calls to offered Protoweft tools, with consent. */
    public static final String TOOL_BRIDGE = "protoweft/tool-bridge";
    /** Chunk events expanded into start/content/end on receipt. */
    public static final String CHUNKS = "chunk-expansion";

    static final Set<String> CLIENT_SUPPORTED = Set.of(BINDING_SSE, RUNS, TEXT, TOOL_CALLS, REASONING, STATE,
            ACTIVITY, SUBAGENTS, PASSTHROUGH, INTERRUPTS, CLIENT_TOOLS, VERSION_NEGOTIATION, CHUNKS, TOOL_BRIDGE,
            CAPABILITIES_ENDPOINT);

    /** {@code protobuf}: whether this client can read the Protobuf binding (protobuf-java present). */
    static ComplianceReport clientReport(boolean protobuf) {
        return report(CLIENT_SUPPORTED, protobuf);
    }

    /** {@code protobuf}: whether this server answers in the Protobuf binding (available and enabled). */
    static ComplianceReport serverReport(boolean protobuf) {
        return report(SERVER_SUPPORTED, protobuf);
    }

    private static ComplianceReport report(Set<String> supported, boolean protobuf) {
        Set<String> all = new java.util.HashSet<>(supported);
        if (protobuf) {
            all.add(BINDING_PROTOBUF);
        }
        return new ComplianceReport(ProtocolId.AGUI, "none", ModuleVersion.VALUE, List.of(AgUiProtocol.VERSION),
                AgUiProtocol.VERSION, Set.copyOf(all), protobuf ? Set.of() : Set.of(BINDING_PROTOBUF));
    }
}
