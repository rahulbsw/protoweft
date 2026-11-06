package io.github.rahulbsw.protoweft.a2a;

import java.util.HashSet;
import java.util.Set;

/**
 * Feature identifiers used in the A2A client's and server's
 * {@link io.github.rahulbsw.protoweft.core.ComplianceReport}. The supported and unsupported sets are the
 * module's honest coverage claim and are asserted by tests.
 */
public final class A2aFeatures {

    /** JSON-RPC 2.0 over HTTP protocol binding. */
    public static final String BINDING_JSONRPC = "binding/jsonrpc";
    /** gRPC protocol binding. */
    public static final String BINDING_GRPC = "binding/grpc";
    /** HTTP+JSON (REST) protocol binding. */
    public static final String BINDING_HTTP_JSON = "binding/http-json";
    /** Public agent card at {@code /.well-known/agent-card.json}. */
    public static final String DISCOVERY_AGENT_CARD = "discovery/agent-card";
    /** {@code GetExtendedAgentCard} for authenticated callers. */
    public static final String DISCOVERY_EXTENDED_AGENT_CARD = "discovery/extended-agent-card";
    /** Agent card signatures (JWS). */
    public static final String DISCOVERY_CARD_SIGNATURES = "discovery/card-signatures";
    public static final String SEND_MESSAGE = "operation/send-message";
    public static final String SEND_STREAMING_MESSAGE = "operation/send-streaming-message";
    public static final String GET_TASK = "operation/get-task";
    public static final String LIST_TASKS = "operation/list-tasks";
    public static final String CANCEL_TASK = "operation/cancel-task";
    public static final String SUBSCRIBE_TO_TASK = "operation/subscribe-to-task";
    /** Push notification config CRUD and webhook delivery. */
    public static final String PUSH_NOTIFICATIONS = "push-notifications";
    /** Multi-turn tasks (input-required / auth-required, then resumed on the same task). */
    public static final String MULTI_TURN = "tasks/multi-turn";
    public static final String CONTENT_TEXT = "content/text";
    public static final String CONTENT_FILE_BYTES = "content/file-bytes";
    public static final String CONTENT_FILE_URI = "content/file-uri";
    public static final String CONTENT_DATA = "content/data";
    /** Static credentials (bearer token / named header) from a {@code CredentialProvider}. */
    public static final String AUTH_STATIC_CREDENTIALS = "auth/static-credentials";
    /** Interpreting or advertising the card's {@code securitySchemes} (OAuth flows, OIDC, mTLS…). */
    public static final String AUTH_SECURITY_SCHEMES = "auth/security-schemes";
    /** Inbound authentication through Protoweft's {@code InboundAuthenticator}. */
    public static final String AUTH_INBOUND_AUTHENTICATOR = "auth/inbound-authenticator";
    /** A2A extensions ({@code A2A-Extensions} header, card extensions). */
    public static final String EXTENSIONS = "extensions";
    /** Tenant-scoped interfaces and cards. */
    public static final String MULTI_TENANCY = "multi-tenancy";

    private static final Set<String> CONTENT = Set.of(CONTENT_TEXT, CONTENT_FILE_BYTES, CONTENT_FILE_URI, CONTENT_DATA);

    static final Set<String> CLIENT_SUPPORTED = union(CONTENT, Set.of(
            BINDING_JSONRPC, DISCOVERY_AGENT_CARD, DISCOVERY_EXTENDED_AGENT_CARD, SEND_MESSAGE,
            SEND_STREAMING_MESSAGE, GET_TASK, CANCEL_TASK, SUBSCRIBE_TO_TASK, MULTI_TURN, AUTH_STATIC_CREDENTIALS));

    static final Set<String> CLIENT_UNSUPPORTED = Set.of(
            BINDING_GRPC, BINDING_HTTP_JSON, DISCOVERY_CARD_SIGNATURES, LIST_TASKS, PUSH_NOTIFICATIONS,
            AUTH_SECURITY_SCHEMES, EXTENSIONS, MULTI_TENANCY);

    /** Server features that are always on; streaming and push depend on the builder. */
    static final Set<String> SERVER_ALWAYS = union(CONTENT, Set.of(
            BINDING_JSONRPC, DISCOVERY_AGENT_CARD, SEND_MESSAGE, GET_TASK, LIST_TASKS, CANCEL_TASK,
            MULTI_TURN, AUTH_INBOUND_AUTHENTICATOR));

    static final Set<String> SERVER_NEVER = Set.of(
            BINDING_GRPC, BINDING_HTTP_JSON, DISCOVERY_CARD_SIGNATURES, AUTH_SECURITY_SCHEMES, EXTENSIONS,
            MULTI_TENANCY);

    private A2aFeatures() {
    }

    static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
