package io.agentweave.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side AgentWeave configuration: local tools and the connectors declared under
 * {@code agentweave.mcp.clients}, {@code agentweave.utcp.manuals} and {@code agentweave.a2a.agents}.
 * Each entry's key is its name in the {@link io.agentweave.core.tool.ToolRegistry}, so its
 * tools appear as {@code <name>.<tool>}.
 */
@ConfigurationProperties("agentweave")
public class AgentWeaveProperties {

    /**
     * Whether a connector that fails to start fails application startup. When false, the
     * failure is logged, the connector is left out of the tool registry and health reports DOWN.
     */
    private boolean failFast = true;

    private final LocalTools localTools = new LocalTools();
    private final Mcp mcp = new Mcp();
    private final Utcp utcp = new Utcp();
    private final A2a a2a = new A2a();

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public LocalTools getLocalTools() {
        return localTools;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Utcp getUtcp() {
        return utcp;
    }

    public A2a getA2a() {
        return a2a;
    }

    /** Discovery of {@code @AgentTool} beans. */
    public static class LocalTools {

        /** Whether {@code @AgentTool} methods of beans are collected into one LocalTools bean. */
        private boolean enabled = true;

        /** The name of the local tools in the tool registry (tools appear as {@code <name>.<tool>}). */
        private String name = "local";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** MCP client connectors. */
    public static class Mcp {

        /** MCP servers to connect to, by registry name. */
        private Map<String, McpClient> clients = new LinkedHashMap<>();

        public Map<String, McpClient> getClients() {
            return clients;
        }

        public void setClients(Map<String, McpClient> clients) {
            this.clients = clients;
        }
    }

    /** One MCP server: either {@code command} (stdio) or {@code url} (Streamable HTTP). */
    public static class McpClient {

        /** Executable of an stdio MCP server, launched as a subprocess. */
        private String command;

        /** Arguments of the stdio command. */
        private List<String> args = new ArrayList<>();

        /** Environment variables of the stdio server process. */
        private Map<String, String> env = new LinkedHashMap<>();

        /** Base URL of a Streamable HTTP MCP server, for example https://mcp.example.com. */
        private String url;

        /** Endpoint path of the HTTP server, resolved against the URL. */
        private String endpoint = "/mcp";

        /** Static headers sent with every HTTP request. */
        private Map<String, String> headers = new LinkedHashMap<>();

        /** Timeout for initialization and each request (connector default: 20 seconds). */
        private Duration requestTimeout;

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    /** UTCP client connectors. */
    public static class Utcp {

        /** UTCP manuals to register, by registry name (also the UTCP manual name). */
        private Map<String, UtcpManual> manuals = new LinkedHashMap<>();

        public Map<String, UtcpManual> getManuals() {
            return manuals;
        }

        public void setManuals(Map<String, UtcpManual> manuals) {
            this.manuals = manuals;
        }
    }

    /** One UTCP manual: either {@code url} (fetched over HTTP) or {@code file}. */
    public static class UtcpManual {

        /** URL of the manual, for example https://api.example.com/utcp (https, or http on loopback). */
        private String url;

        /** A manual JSON file. */
        private Path file;

        /** Static headers on the manual discovery request. */
        private Map<String, String> headers = new LinkedHashMap<>();

        /**
         * Variables referenced by the manual's call templates as ${NAME}, keyed by NAME. They are
         * namespaced by the manual name as UTCP requires; unset ones fall back to the environment.
         */
        private Map<String, String> variables = new LinkedHashMap<>();

        /** Manual discovery timeout (connector default: 10 seconds). */
        private Duration discoveryTimeout;

        /** Tool call timeout (connector default: 30 seconds). */
        private Duration callTimeout;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Path getFile() {
            return file;
        }

        public void setFile(Path file) {
            this.file = file;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Map<String, String> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, String> variables) {
            this.variables = variables;
        }

        public Duration getDiscoveryTimeout() {
            return discoveryTimeout;
        }

        public void setDiscoveryTimeout(Duration discoveryTimeout) {
            this.discoveryTimeout = discoveryTimeout;
        }

        public Duration getCallTimeout() {
            return callTimeout;
        }

        public void setCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
        }
    }

    /** A2A client connectors. */
    public static class A2a {

        /** Remote A2A agents, by registry name; each is offered as the tool {@code <name>.ask}. */
        private Map<String, A2aAgent> agents = new LinkedHashMap<>();

        public Map<String, A2aAgent> getAgents() {
            return agents;
        }

        public void setAgents(Map<String, A2aAgent> agents) {
            this.agents = agents;
        }
    }

    /** One remote A2A agent. */
    public static class A2aAgent {

        /** Base URL of the agent; its card is read from {@code <url>/.well-known/agent-card.json}. */
        private String url;

        /** Connect timeout and the longest a call waits for the task (connector default: 60 seconds). */
        private Duration timeout;

        /** Whether calls may stream when the agent supports it. */
        private boolean streaming = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }
    }
}
