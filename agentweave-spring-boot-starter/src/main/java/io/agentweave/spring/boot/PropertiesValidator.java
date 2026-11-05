package io.agentweave.spring.boot;

import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Boundary checks on AgentWeave properties, reported through Spring Boot's failure analysis. */
final class PropertiesValidator {

    static final String MCP_CLASS = "io.agentweave.mcp.McpClientConnector";
    static final String UTCP_CLASS = "io.agentweave.utcp.UtcpClientConnector";
    static final String A2A_CLASS = "io.agentweave.a2a.A2aAgentConnector";

    private static final Pattern SOURCE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private PropertiesValidator() {
    }

    static void validateClients(AgentWeaveProperties props, ClassLoader classLoader) {
        Map<String, String> names = new HashMap<>();
        if (props.getLocalTools().isEnabled()) {
            name(names, "agentweave.local-tools.name", props.getLocalTools().getName());
        }
        props.getMcp().getClients().forEach((name, c) -> {
            String key = "agentweave.mcp.clients." + name;
            name(names, key, name);
            requireModule(key, MCP_CLASS, "agentweave-mcp", classLoader);
            boolean command = hasText(c.getCommand());
            boolean url = hasText(c.getUrl());
            if (command == url) {
                throw invalid(key, command ? "command and url" : null,
                        "set exactly one of 'command' (stdio) or 'url' (Streamable HTTP)");
            }
            if (url && (c.getEndpoint() == null || !c.getEndpoint().startsWith("/"))) {
                throw invalid(key + ".endpoint", c.getEndpoint(), "must start with '/'");
            }
            if (command && !c.getHeaders().isEmpty()) {
                throw invalid(key + ".headers", c.getHeaders(), "headers apply to 'url' servers only");
            }
            if (url && (!c.getArgs().isEmpty() || !c.getEnv().isEmpty())) {
                throw invalid(key + ".args", c.getArgs(), "args and env apply to 'command' servers only");
            }
        });
        props.getUtcp().getManuals().forEach((name, m) -> {
            String key = "agentweave.utcp.manuals." + name;
            name(names, key, name);
            requireModule(key, UTCP_CLASS, "agentweave-utcp", classLoader);
            boolean url = hasText(m.getUrl());
            if (url == (m.getFile() != null)) {
                throw invalid(key, url ? "url and file" : null, "set exactly one of 'url' or 'file'");
            }
            if (!url && !m.getHeaders().isEmpty()) {
                throw invalid(key + ".headers", m.getHeaders(), "headers apply to 'url' manuals only");
            }
        });
        props.getA2a().getAgents().forEach((name, a) -> {
            String key = "agentweave.a2a.agents." + name;
            name(names, key, name);
            requireModule(key, A2A_CLASS, "agentweave-a2a", classLoader);
            if (!hasText(a.getUrl())) {
                throw invalid(key + ".url", a.getUrl(), "the agent's base URL is required");
            }
        });
    }

    static void validateServers(AgentWeaveServerProperties props, ClassLoader classLoader) {
        Map<String, String> paths = new HashMap<>();
        if (props.getMcp().isEnabled()) {
            requireModule("agentweave.server.mcp.enabled", "io.agentweave.mcp.McpToolServer", "agentweave-mcp", classLoader);
            path(paths, "agentweave.server.mcp.path", props.getMcp().getPath());
        }
        if (props.getUtcp().isEnabled()) {
            requireModule("agentweave.server.utcp.enabled", "io.agentweave.utcp.UtcpManualProvider", "agentweave-utcp",
                    classLoader);
            path(paths, "agentweave.server.utcp (fixed)", "/utcp");
            if (hasText(props.getUtcp().getApiKeyHeader()) != hasText(props.getUtcp().getApiKeyVariable())) {
                throw invalid("agentweave.server.utcp.api-key-header", props.getUtcp().getApiKeyHeader(),
                        "set api-key-header and api-key-variable together");
            }
            String base = props.getUtcp().getBaseUrl();
            if (base != null && !base.matches("https?://[^\\s$]+")) {
                throw invalid("agentweave.server.utcp.base-url", base, "must be an absolute http(s) URL without '$'");
            }
        }
        if (props.getA2a().isEnabled()) {
            requireModule("agentweave.server.a2a.enabled", "io.agentweave.a2a.A2aAgentServer", "agentweave-a2a",
                    classLoader);
            path(paths, "agentweave.server.a2a.path", props.getA2a().getPath());
            String url = props.getA2a().getPublicUrl();
            if (url != null && !url.matches("https?://\\S+")) {
                throw invalid("agentweave.server.a2a.public-url", url, "must be an absolute http(s) URL");
            }
            for (int i = 0; i < props.getA2a().getSkills().size(); i++) {
                if (!hasText(props.getA2a().getSkills().get(i).getId())) {
                    throw invalid("agentweave.server.a2a.skills[" + i + "].id", null, "a skill needs an id");
                }
            }
        }
    }

    private static void name(Map<String, String> seen, String key, String name) {
        if (name == null || !SOURCE_NAME.matcher(name).matches()) {
            throw invalid(key, name, "registry names must match " + SOURCE_NAME);
        }
        String previous = seen.putIfAbsent(name, key);
        if (previous != null) {
            throw invalid(key, name, "name already used by " + previous);
        }
    }

    private static void path(Map<String, String> seen, String key, String path) {
        if (path == null || !path.startsWith("/") || path.contains("*")) {
            throw invalid(key, path, "must start with '/' and contain no wildcard");
        }
        String root = path.equals("/utcp") || path.startsWith("/utcp/") ? "/utcp" : path;
        String previous = seen.putIfAbsent(root, key);
        if (previous != null) {
            throw invalid(key, path, "path already used by " + previous);
        }
    }

    private static void requireModule(String key, String className, String artifact, ClassLoader classLoader) {
        if (!ClassUtils.isPresent(className, classLoader)) {
            throw invalid(key, null, "add the io.agentweave:" + artifact + " dependency to use this protocol");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static InvalidConfigurationPropertyValueException invalid(String key, Object value, String reason) {
        return new InvalidConfigurationPropertyValueException(key, value, reason);
    }
}
