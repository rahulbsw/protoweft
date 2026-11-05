package io.agentweave.mcp;

import java.util.HashSet;
import java.util.Set;

/**
 * Feature identifiers used in the {@link io.agentweave.core.ComplianceReport} of the MCP client
 * ({@link McpClientConnector}) and server ({@link McpToolServer}). An id names a protocol
 * feature; the report's owner gives the role. Each report lists only the transport actually in
 * use as supported, and tests assert both sets.
 */
public final class McpFeatures {

    public static final String LIFECYCLE_VERSION_NEGOTIATION = "lifecycle/version-negotiation";
    public static final String TRANSPORT_STDIO = "transport/stdio";
    public static final String TRANSPORT_STREAMABLE_HTTP = "transport/streamable-http";
    public static final String TRANSPORT_SSE = "transport/sse";
    public static final String AUTHORIZATION = "authorization";
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";
    public static final String TOOLS_STRUCTURED_OUTPUT = "tools/structured-output";
    public static final String TOOLS_LIST_CHANGED = "tools/list-changed";
    public static final String CONTENT_TEXT = "content/text";
    public static final String CONTENT_IMAGE = "content/image";
    public static final String CONTENT_AUDIO = "content/audio";
    public static final String CONTENT_RESOURCE = "content/resource";
    public static final String CONTENT_RESOURCE_LINK = "content/resource-link";
    public static final String RESOURCES = "resources";
    public static final String PROMPTS = "prompts";
    public static final String COMPLETION = "completion";
    public static final String LOGGING = "logging";
    public static final String PROGRESS = "progress";
    public static final String CANCELLATION = "cancellation";
    public static final String SAMPLING = "sampling";
    public static final String ROOTS = "roots";
    public static final String ELICITATION = "elicitation";

    /** Implemented by both the client connector and the tool server, on either transport. */
    private static final Set<String> COMMON = Set.of(
            LIFECYCLE_VERSION_NEGOTIATION, TOOLS_LIST, TOOLS_CALL, TOOLS_STRUCTURED_OUTPUT,
            CONTENT_TEXT, CONTENT_IMAGE, CONTENT_AUDIO, CONTENT_RESOURCE_LINK);

    /** Defined by MCP, implemented by neither role. */
    private static final Set<String> NOT_IMPLEMENTED = Set.of(
            TRANSPORT_SSE, AUTHORIZATION, TOOLS_LIST_CHANGED, CONTENT_RESOURCE, RESOURCES, PROMPTS,
            COMPLETION, LOGGING, PROGRESS, CANCELLATION, SAMPLING, ROOTS, ELICITATION);

    private static final Set<String> TRANSPORTS = Set.of(TRANSPORT_STDIO, TRANSPORT_STREAMABLE_HTTP);

    /** What a client or server using {@code transport} implements. */
    static Set<String> supported(String transport) {
        requireTransport(transport);
        Set<String> s = new HashSet<>(COMMON);
        s.add(transport);
        return Set.copyOf(s);
    }

    /** What a client or server using {@code transport} does not implement, including the other transport. */
    static Set<String> unsupported(String transport) {
        requireTransport(transport);
        Set<String> s = new HashSet<>(NOT_IMPLEMENTED);
        s.addAll(TRANSPORTS);
        s.remove(transport);
        return Set.copyOf(s);
    }

    private static void requireTransport(String transport) {
        if (!TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("not an implemented transport: " + transport);
        }
    }

    private McpFeatures() {
    }
}
