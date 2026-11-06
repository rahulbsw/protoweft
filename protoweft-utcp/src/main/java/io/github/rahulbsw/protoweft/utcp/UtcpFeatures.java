package io.github.rahulbsw.protoweft.utcp;

import java.util.Set;

/**
 * Feature identifiers used in the UTCP connector's {@link io.github.rahulbsw.protoweft.core.ComplianceReport}.
 * The supported and unsupported sets are the connector's honest coverage claim and are asserted
 * by tests.
 */
public final class UtcpFeatures {

    // Manual call templates (how a manual is obtained).
    public static final String MANUAL_HTTP = "manual/http";
    public static final String MANUAL_TEXT = "manual/text";
    public static final String MANUAL_FILE = "manual/file";
    public static final String MANUAL_YAML = "manual/yaml";
    public static final String MANUAL_OPENAPI = "manual/openapi-conversion";
    public static final String ALLOWED_COMMUNICATION_PROTOCOLS = "manual/allowed-communication-protocols";

    // Tool call templates (how a tool is called).
    public static final String CALL_HTTP = "call-template/http";
    public static final String CALL_TEXT = "call-template/text";
    public static final String CALL_FILE = "call-template/file";
    public static final String CALL_SSE = "call-template/sse";
    public static final String CALL_STREAMABLE_HTTP = "call-template/streamable_http";
    public static final String CALL_CLI = "call-template/cli";
    public static final String CALL_WEBSOCKET = "call-template/websocket";
    public static final String CALL_MCP = "call-template/mcp";
    public static final String CALL_GRAPHQL = "call-template/graphql";
    public static final String CALL_TCP = "call-template/tcp";
    public static final String CALL_UDP = "call-template/udp";

    // Authentication objects.
    public static final String AUTH_API_KEY = "auth/api_key";
    public static final String AUTH_BASIC = "auth/basic";
    public static final String AUTH_OAUTH2 = "auth/oauth2";
    /** {@code auth_tools}, which only applies to tools generated from OpenAPI. */
    public static final String AUTH_TOOLS = "auth/auth_tools";

    // Client behavior.
    public static final String VARIABLES = "variables/substitution";
    public static final String VARIABLES_DOTENV = "variables/dotenv-loader";
    public static final String SEARCH_TAG_AND_DESCRIPTION = "search/tag_and_description_word_match";
    public static final String SEARCH_EMBEDDINGS = "search/in_mem_embeddings";
    public static final String CALL_TOOL_STREAMING = "client/call-tool-streaming";
    public static final String POST_PROCESSING = "client/post-processing";
    public static final String HTTP_SECURITY = "security/https-or-loopback";

    static final Set<String> SUPPORTED = Set.of(
            MANUAL_HTTP, MANUAL_TEXT, MANUAL_FILE, ALLOWED_COMMUNICATION_PROTOCOLS,
            CALL_HTTP, CALL_TEXT,
            AUTH_API_KEY, AUTH_BASIC, AUTH_OAUTH2,
            VARIABLES, VARIABLES_DOTENV, SEARCH_TAG_AND_DESCRIPTION, HTTP_SECURITY);

    static final Set<String> UNSUPPORTED = Set.of(
            MANUAL_YAML, MANUAL_OPENAPI,
            CALL_FILE, CALL_SSE, CALL_STREAMABLE_HTTP, CALL_CLI, CALL_WEBSOCKET, CALL_MCP, CALL_GRAPHQL, CALL_TCP,
            CALL_UDP,
            AUTH_TOOLS, SEARCH_EMBEDDINGS, CALL_TOOL_STREAMING, POST_PROCESSING);

    /** Tool {@code call_template_type}s this connector can call. */
    static final Set<String> CALLABLE_TEMPLATE_TYPES = Set.of("http", "text");

    private UtcpFeatures() {
    }
}
