package io.github.rahulbsw.protoweft.mcp;

import java.util.HashSet;
import java.util.Set;

/**
 * Feature identifiers used in the {@link io.github.rahulbsw.protoweft.core.ComplianceReport} of the MCP client
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
    public static final String RESOURCES_SUBSCRIBE = "resources/subscribe";
    public static final String RESOURCES_LIST_CHANGED = "resources/list-changed";
    public static final String PROMPTS = "prompts";
    public static final String PROMPTS_LIST_CHANGED = "prompts/list-changed";
    public static final String COMPLETION = "completion";
    public static final String LOGGING = "logging";
    public static final String PROGRESS = "progress";
    public static final String CANCELLATION = "cancellation";
    public static final String SAMPLING = "sampling";
    public static final String ROOTS = "roots";
    public static final String ELICITATION = "elicitation";

    /** Which side of the protocol a report describes. */
    enum Role { CLIENT, SERVER }

    /** Implemented by both the client connector and the tool server, on either transport. */
    private static final Set<String> COMMON = Set.of(
            LIFECYCLE_VERSION_NEGOTIATION, TOOLS_LIST, TOOLS_CALL, TOOLS_STRUCTURED_OUTPUT,
            CONTENT_TEXT, CONTENT_IMAGE, CONTENT_AUDIO, CONTENT_RESOURCE, CONTENT_RESOURCE_LINK,
            RESOURCES, RESOURCES_SUBSCRIBE, PROMPTS, COMPLETION, LOGGING, PROGRESS);

    /**
     * Implemented by the client only: it listens for list changes, while the server's lists
     * are snapshots taken when it is built.
     */
    private static final Set<String> CLIENT_ONLY = Set.of(TOOLS_LIST_CHANGED, RESOURCES_LIST_CHANGED,
            PROMPTS_LIST_CHANGED);

    /** Defined by MCP, implemented by neither role. */
    private static final Set<String> NOT_IMPLEMENTED = Set.of(
            TRANSPORT_SSE, AUTHORIZATION, CANCELLATION, SAMPLING, ROOTS, ELICITATION);

    private static final Set<String> TRANSPORTS = Set.of(TRANSPORT_STDIO, TRANSPORT_STREAMABLE_HTTP);

    /** What {@code role} implements over {@code transport}. */
    static Set<String> supported(Role role, String transport) {
        requireTransport(transport);
        Set<String> s = new HashSet<>(COMMON);
        s.add(transport);
        if (role == Role.CLIENT) {
            s.addAll(CLIENT_ONLY);
        }
        return Set.copyOf(s);
    }

    /** What {@code role} does not implement over {@code transport}, including the other transport. */
    static Set<String> unsupported(Role role, String transport) {
        requireTransport(transport);
        Set<String> s = new HashSet<>(NOT_IMPLEMENTED);
        s.addAll(TRANSPORTS);
        s.remove(transport);
        if (role == Role.SERVER) {
            s.addAll(CLIENT_ONLY);
        }
        return Set.copyOf(s);
    }

    /**
     * The client's coverage: {@link #AUTHORIZATION} moves to supported when an
     * {@code HttpAuthorizer} (for example protoweft-oauth's {@code OAuthClient}) is configured.
     */
    static Set<String> clientSupported(String transport, boolean authorizer) {
        Set<String> s = new HashSet<>(supported(Role.CLIENT, transport));
        if (authorizer) {
            s.add(AUTHORIZATION);
        }
        return Set.copyOf(s);
    }

    static Set<String> clientUnsupported(String transport, boolean authorizer) {
        Set<String> s = new HashSet<>(unsupported(Role.CLIENT, transport));
        if (authorizer) {
            s.remove(AUTHORIZATION);
        }
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
