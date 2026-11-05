package io.agentweave.mcp;

import java.util.Set;

/**
 * Feature identifiers used in the MCP connector's
 * {@link io.agentweave.core.ComplianceReport}. The supported and unsupported sets are the
 * connector's honest coverage claim and are asserted by tests.
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

    static final Set<String> SUPPORTED = Set.of(
            LIFECYCLE_VERSION_NEGOTIATION, TRANSPORT_STDIO, TOOLS_LIST, TOOLS_CALL,
            TOOLS_STRUCTURED_OUTPUT, CONTENT_TEXT, CONTENT_IMAGE, CONTENT_AUDIO, CONTENT_RESOURCE_LINK);

    static final Set<String> UNSUPPORTED = Set.of(
            TRANSPORT_STREAMABLE_HTTP, TRANSPORT_SSE, AUTHORIZATION, TOOLS_LIST_CHANGED,
            CONTENT_RESOURCE, RESOURCES, PROMPTS, COMPLETION, LOGGING,
            PROGRESS, CANCELLATION, SAMPLING, ROOTS, ELICITATION);

    private McpFeatures() {
    }
}
