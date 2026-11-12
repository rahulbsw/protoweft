package io.github.rahulbsw.protoweft.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Protoweft server endpoints. Every protocol is off until {@code protoweft.server.<protocol>.enabled=true}.
 */
@ConfigurationProperties("protoweft.server")
public class ProtoweftServerProperties {

    /**
     * Order of the inbound-authentication filters. The default (0) runs them after Spring
     * Security's filter chain (-100), so Spring Security sees requests first.
     */
    private int authFilterOrder = 0;

    private final McpServer mcp = new McpServer();
    private final UtcpServer utcp = new UtcpServer();
    private final A2aServer a2a = new A2aServer();
    private final RapServer rap = new RapServer();
    private final AgUiServer agui = new AgUiServer();

    public int getAuthFilterOrder() {
        return authFilterOrder;
    }

    public void setAuthFilterOrder(int authFilterOrder) {
        this.authFilterOrder = authFilterOrder;
    }

    public McpServer getMcp() {
        return mcp;
    }

    public UtcpServer getUtcp() {
        return utcp;
    }

    public A2aServer getA2a() {
        return a2a;
    }

    public RapServer getRap() {
        return rap;
    }

    public AgUiServer getAgui() {
        return agui;
    }

    /** The AG-UI agent endpoint (HTTP + SSE), for browser frontends such as CopilotKit. */
    public static class AgUiServer {

        /** Whether to serve the application's agent over AG-UI. */
        private boolean enabled;

        /** Serve the application's Spring AI ChatClient as the agent (when there is no AgentHandler or AgUiAgent bean). */
        private boolean fromChatClient;

        /** The agent's path; its capabilities document is at {@code <path>/capabilities}. */
        private String path = "/agui";

        /** Browser origins allowed to call the agent cross-origin ({@code *} for any); empty means no CORS. */
        private List<String> allowedOrigins = new ArrayList<>();

        /** The agent's name, published in its capabilities document. */
        private String name;

        /** What the agent does, published in its capabilities document. */
        private String description;

        /**
         * Answer in AG-UI's HTTP + Protobuf binding when a client's {@code Accept} admits it (a
         * wildcard does too). Takes effect only with protobuf-java (4.36.2 or newer) on the
         * classpath; false always answers SSE.
         */
        private boolean protobuf = true;

        public boolean isProtobuf() {
            return protobuf;
        }

        public void setProtobuf(boolean protobuf) {
            this.protobuf = protobuf;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFromChatClient() {
            return fromChatClient;
        }

        public void setFromChatClient(boolean fromChatClient) {
            this.fromChatClient = fromChatClient;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    /** Which tools a tool server publishes. */
    public enum Exposure {
        /** Only this application's local tools (the {@code LocalTools} bean). */
        LOCAL,
        /** The whole tool registry: local tools and every connected remote source, as {@code <source>.<tool>}. */
        REGISTRY
    }

    /** The MCP server (Streamable HTTP). */
    public static class McpServer {

        /** Whether to serve tools over MCP. */
        private boolean enabled;

        /** Servlet path of the MCP endpoint. */
        private String path = "/mcp";

        /** Which tools to serve. */
        private Exposure expose = Exposure.LOCAL;

        /** serverInfo name sent during initialization. */
        private String serverName = "protoweft";

        /** serverInfo version sent during initialization. */
        private String serverVersion = "0.1.0";

        /** Browser origins allowed to call the endpoint; requests with any other Origin get 403. */
        private List<String> allowedOrigins = new ArrayList<>();

        /** Longest a tools/call may run (server default: 60 seconds). */
        private Duration requestTimeout;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Exposure getExpose() {
            return expose;
        }

        public void setExpose(Exposure expose) {
            this.expose = expose;
        }

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public String getServerVersion() {
            return serverVersion;
        }

        public void setServerVersion(String serverVersion) {
            this.serverVersion = serverVersion;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    /** The UTCP manual provider ({@code GET /utcp}, {@code POST /utcp/tools/<tool>}). */
    public static class UtcpServer {

        /** Whether to publish tools as a UTCP manual. */
        private boolean enabled;

        /**
         * Public base URL used in the manual's tool URLs, for example https://api.example.com. When
         * unset it is derived from each request, which is correct behind a proxy only with
         * server.forward-headers-strategy set.
         */
        private String baseUrl;

        /** Which tools to publish. */
        private Exposure expose = Exposure.LOCAL;

        /** manual_version of the published manual. */
        private String manualVersion = "1.0.0";

        /** Header that clients must send on tool calls (published as api_key auth); requires api-key-variable. */
        private String apiKeyHeader;

        /** Client-side UTCP variable holding the value of api-key-header. */
        private String apiKeyVariable;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Exposure getExpose() {
            return expose;
        }

        public void setExpose(Exposure expose) {
            this.expose = expose;
        }

        public String getManualVersion() {
            return manualVersion;
        }

        public void setManualVersion(String manualVersion) {
            this.manualVersion = manualVersion;
        }

        public String getApiKeyHeader() {
            return apiKeyHeader;
        }

        public void setApiKeyHeader(String apiKeyHeader) {
            this.apiKeyHeader = apiKeyHeader;
        }

        public String getApiKeyVariable() {
            return apiKeyVariable;
        }

        public void setApiKeyVariable(String apiKeyVariable) {
            this.apiKeyVariable = apiKeyVariable;
        }
    }

    /** The A2A agent server (agent card + JSON-RPC), backed by an {@code AgentHandler} bean. */
    public static class A2aServer {

        /** Whether to serve the AgentHandler bean over A2A. */
        private boolean enabled;

        /**
         * With Spring AI on the classpath and no AgentHandler bean: serve the application's
         * ChatClient as the A2A agent (streaming when {@code streaming} is true).
         */
        private boolean fromChatClient;

        public boolean isFromChatClient() {
            return fromChatClient;
        }

        public void setFromChatClient(boolean fromChatClient) {
            this.fromChatClient = fromChatClient;
        }

        /**
         * Externally reachable base URL advertised in the agent card. When unset,
         * http://localhost:<port><context-path> is used, which only local clients can reach.
         */
        private String publicUrl;

        /** Servlet path of the JSON-RPC endpoint. */
        private String path = "/a2a";

        /** Agent name in the card (default: spring.application.name). Ignored with an AgentDescriptor bean. */
        private String name;

        /** Agent description in the card. */
        private String description = "";

        /** Agent version in the card. */
        private String version = "1.0.0";

        /** Advertised skills. */
        private List<Skill> skills = new ArrayList<>();

        /** Whether to offer streaming (SendStreamingMessage, SubscribeToTask). */
        private boolean streaming = true;

        /** Whether to offer push notifications. */
        private boolean pushNotifications = true;

        /** How long a blocking SendMessage waits for the handler (server default: 30 seconds). */
        private Duration agentTimeout;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPublicUrl() {
            return publicUrl;
        }

        public void setPublicUrl(String publicUrl) {
            this.publicUrl = publicUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public List<Skill> getSkills() {
            return skills;
        }

        public void setSkills(List<Skill> skills) {
            this.skills = skills;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public boolean isPushNotifications() {
            return pushNotifications;
        }

        public void setPushNotifications(boolean pushNotifications) {
            this.pushNotifications = pushNotifications;
        }

        public Duration getAgentTimeout() {
            return agentTimeout;
        }

        public void setAgentTimeout(Duration agentTimeout) {
            this.agentTimeout = agentTimeout;
        }
    }

    /**
     * The RAP (Reactive Agent Protocol) tool server: {@code GET <base-path>/.well-known/rap-toolset},
     * {@code POST <base-path>/invoke}, {@code /close_thread}, {@code /cancel_tool_call} and
     * {@code /user_choice_response}.
     */
    public static class RapServer {

        /** Whether to serve tools over RAP. */
        private boolean enabled;

        /** Path every RAP route is mounted under; "/" mounts them at the root. */
        private String basePath = "/rap";

        /**
         * Public URL runtimes use as this server's base (where .well-known/rap-toolset lives,
         * including the base path), used in the toolset's endpoint and in choice response URLs.
         * When unset it is derived from each request.
         */
        private String baseUrl;

        /** Which tools to serve. */
        private Exposure expose = Exposure.LOCAL;

        /** Toolset name (1 to 128 characters); also the server's key in the RAP store. */
        private String name = "protoweft";

        /** Toolset description. */
        private String description;

        /**
         * Hosts the server may also post callbacks to over plain http or https, beyond the default
         * policy (https to non-link-local hosts, http to loopback).
         */
        private List<String> allowedCallbackHosts = new ArrayList<>();

        /** How long a tool's user-choice prompt waits before using the default (server default: 10 minutes). */
        private Duration choiceTimeout;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Exposure getExpose() {
            return expose;
        }

        public void setExpose(Exposure expose) {
            this.expose = expose;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getAllowedCallbackHosts() {
            return allowedCallbackHosts;
        }

        public void setAllowedCallbackHosts(List<String> allowedCallbackHosts) {
            this.allowedCallbackHosts = allowedCallbackHosts;
        }

        public Duration getChoiceTimeout() {
            return choiceTimeout;
        }

        public void setChoiceTimeout(Duration choiceTimeout) {
            this.choiceTimeout = choiceTimeout;
        }
    }

    /** One advertised A2A skill. */
    public static class Skill {

        /** Stable skill identifier. */
        private String id;

        /** Display name (default: the id). */
        private String name;

        /** What the skill does. */
        private String description = "";

        /** Discovery keywords. */
        private List<String> tags = new ArrayList<>();

        /** Example prompts. */
        private List<String> examples = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public List<String> getExamples() {
            return examples;
        }

        public void setExamples(List<String> examples) {
            this.examples = examples;
        }
    }
}
