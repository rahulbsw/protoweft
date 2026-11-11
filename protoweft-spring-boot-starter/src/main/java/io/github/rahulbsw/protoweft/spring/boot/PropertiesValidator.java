package io.github.rahulbsw.protoweft.spring.boot;

import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Boundary checks on Protoweft properties, reported through Spring Boot's failure analysis. */
final class PropertiesValidator {

    static final String MCP_CLASS = "io.github.rahulbsw.protoweft.mcp.McpClientConnector";
    static final String UTCP_CLASS = "io.github.rahulbsw.protoweft.utcp.UtcpClientConnector";
    static final String A2A_CLASS = "io.github.rahulbsw.protoweft.a2a.A2aAgentConnector";
    static final String RAP_CLASS = "io.github.rahulbsw.protoweft.rap.RapClientConnector";
    static final String RAP_SERVER_CLASS = "io.github.rahulbsw.protoweft.rap.RapToolServer";
    static final String AGUI_CLASS = "io.github.rahulbsw.protoweft.agui.AgUiClientConnector";
    static final String AGUI_SERVER_CLASS = "io.github.rahulbsw.protoweft.agui.AgUiAgentServer";
    /** Where {@code RapCallbackEndpoint} is mounted (fixed by protoweft-rap). */
    static final String RAP_CALLBACK_PATH = "/rap/callback";

    private static final String HTTP_URL = "https?://\\S+";

    private static final Pattern SOURCE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private PropertiesValidator() {
    }

    static void validateClients(ProtoweftProperties props, ClassLoader classLoader) {
        Map<String, String> names = new HashMap<>();
        if (props.getLocalTools().isEnabled()) {
            name(names, "protoweft.local-tools.name", props.getLocalTools().getName());
        }
        props.getMcp().getClients().forEach((name, c) -> {
            String key = "protoweft.mcp.clients." + name;
            name(names, key, name);
            requireModule(key, MCP_CLASS, "protoweft-mcp", classLoader);
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
            String key = "protoweft.utcp.manuals." + name;
            name(names, key, name);
            requireModule(key, UTCP_CLASS, "protoweft-utcp", classLoader);
            boolean url = hasText(m.getUrl());
            if (url == (m.getFile() != null)) {
                throw invalid(key, url ? "url and file" : null, "set exactly one of 'url' or 'file'");
            }
            if (!url && !m.getHeaders().isEmpty()) {
                throw invalid(key + ".headers", m.getHeaders(), "headers apply to 'url' manuals only");
            }
        });
        props.getA2a().getAgents().forEach((name, a) -> {
            String key = "protoweft.a2a.agents." + name;
            name(names, key, name);
            requireModule(key, A2A_CLASS, "protoweft-a2a", classLoader);
            if (!hasText(a.getUrl())) {
                throw invalid(key + ".url", a.getUrl(), "the agent's base URL is required");
            }
        });
        props.getRap().getServers().forEach((name, r) -> {
            String key = "protoweft.rap.servers." + name;
            name(names, key, name);
            requireModule(key, RAP_CLASS, "protoweft-rap", classLoader);
            if (!hasText(r.getUrl())) {
                throw invalid(key + ".url", r.getUrl(), "the tool server's base URL is required");
            }
            httpUrl(key + ".url", r.getUrl());
            for (int i = 0; i < r.getAdditionalUrls().size(); i++) {
                httpUrl(key + ".additional-urls[" + i + "]", r.getAdditionalUrls().get(i));
            }
            if (r.getThreadId() != null && r.getThreadId().isEmpty()) {
                throw invalid(key + ".thread-id", r.getThreadId(), "must not be empty");
            }
        });
        props.getAgui().getAgents().forEach((name, a) -> {
            String key = "protoweft.agui.agents." + name;
            name(names, key, name);
            requireModule(key, AGUI_CLASS, "protoweft-agui", classLoader);
            if (!hasText(a.getUrl())) {
                throw invalid(key + ".url", a.getUrl(), "the agent's endpoint URL is required");
            }
            httpUrl(key + ".url", a.getUrl());
        });
        String callbackUrl = props.getRap().getCallback().getPublicBaseUrl();
        if (callbackUrl != null) {
            httpUrl("protoweft.rap.callback.public-base-url", callbackUrl);
        }
    }

    static void validateServers(ProtoweftServerProperties props, ClassLoader classLoader) {
        Map<String, String> paths = new HashMap<>();
        if (props.getMcp().isEnabled()) {
            requireModule("protoweft.server.mcp.enabled", "io.github.rahulbsw.protoweft.mcp.McpToolServer", "protoweft-mcp", classLoader);
            path(paths, "protoweft.server.mcp.path", props.getMcp().getPath());
        }
        if (props.getUtcp().isEnabled()) {
            requireModule("protoweft.server.utcp.enabled", "io.github.rahulbsw.protoweft.utcp.UtcpManualProvider", "protoweft-utcp",
                    classLoader);
            path(paths, "protoweft.server.utcp (fixed)", "/utcp");
            if (hasText(props.getUtcp().getApiKeyHeader()) != hasText(props.getUtcp().getApiKeyVariable())) {
                throw invalid("protoweft.server.utcp.api-key-header", props.getUtcp().getApiKeyHeader(),
                        "set api-key-header and api-key-variable together");
            }
            String base = props.getUtcp().getBaseUrl();
            if (base != null && !base.matches("https?://[^\\s$]+")) {
                throw invalid("protoweft.server.utcp.base-url", base, "must be an absolute http(s) URL without '$'");
            }
        }
        if (props.getA2a().isEnabled()) {
            requireModule("protoweft.server.a2a.enabled", "io.github.rahulbsw.protoweft.a2a.A2aAgentServer", "protoweft-a2a",
                    classLoader);
            path(paths, "protoweft.server.a2a.path", props.getA2a().getPath());
            String url = props.getA2a().getPublicUrl();
            if (url != null && !url.matches("https?://\\S+")) {
                throw invalid("protoweft.server.a2a.public-url", url, "must be an absolute http(s) URL");
            }
            for (int i = 0; i < props.getA2a().getSkills().size(); i++) {
                if (!hasText(props.getA2a().getSkills().get(i).getId())) {
                    throw invalid("protoweft.server.a2a.skills[" + i + "].id", null, "a skill needs an id");
                }
            }
        }
        if (props.getAgui().isEnabled()) {
            requireModule("protoweft.server.agui.enabled", AGUI_SERVER_CLASS, "protoweft-agui", classLoader);
            String path = props.getAgui().getPath();
            if (path == null || !path.startsWith("/") || path.endsWith("/") || path.contains("*")) {
                throw invalid("protoweft.server.agui.path", path, "must start with '/', not end with '/', and have no '*'");
            }
            path(paths, "protoweft.server.agui.path", path);
        }
        if (props.getRap().isEnabled()) {
            requireModule("protoweft.server.rap.enabled", RAP_SERVER_CLASS, "protoweft-rap", classLoader);
            rapBasePath(paths, props.getRap().getBasePath());
            if (props.getRap().getBaseUrl() != null) {
                httpUrl("protoweft.server.rap.base-url", props.getRap().getBaseUrl());
            }
            String name = props.getRap().getName();
            if (name == null || name.isEmpty() || name.length() > 128) {
                throw invalid("protoweft.server.rap.name", name, "the toolset name must be 1 to 128 characters");
            }
        }
    }

    /** A RAP base path is exact, may be "/" (the root), and stays clear of the callback endpoint. */
    private static void rapBasePath(Map<String, String> paths, String basePath) {
        String key = "protoweft.server.rap.base-path";
        if ("/".equals(basePath)) {
            return;
        }
        path(paths, key, basePath);
        if (basePath.endsWith("/") || basePath.equals(RAP_CALLBACK_PATH) || basePath.startsWith(RAP_CALLBACK_PATH + "/")) {
            throw invalid(key, basePath, "must not end with '/' or lie under " + RAP_CALLBACK_PATH
                    + ", where RAP callbacks are received");
        }
    }

    private static void httpUrl(String key, String url) {
        if (url == null || !url.matches(HTTP_URL)) {
            throw invalid(key, url, "must be an absolute http(s) URL");
        }
    }

    /** Discovery needs its module (and protoweft-mcp to serve MCP) and a valid endpoint path. */
    static void validateDiscovery(ProtoweftDiscoveryProperties props, ClassLoader classLoader) {
        requireModule("protoweft.discovery.enabled", ProtoweftDiscoveryAutoConfiguration.CATALOG_CLASS,
                "protoweft-discovery", classLoader);
        if (props.isExposeMcp()) {
            requireModule("protoweft.discovery.expose-mcp", ProtoweftDiscoveryAutoConfiguration.MCP_SERVER_CLASS,
                    "protoweft-mcp", classLoader);
            path(new HashMap<>(), "protoweft.discovery.mcp-path", props.getMcpPath());
        }
    }

    /** The discovery MCP path must not collide with an enabled server's path. */
    static void validateDiscoveryPath(ProtoweftDiscoveryProperties discovery, ProtoweftServerProperties props) {
        Map<String, String> paths = new HashMap<>();
        if (props.getMcp().isEnabled()) {
            path(paths, "protoweft.server.mcp.path", props.getMcp().getPath());
        }
        if (props.getUtcp().isEnabled()) {
            path(paths, "protoweft.server.utcp (fixed)", "/utcp");
        }
        if (props.getA2a().isEnabled()) {
            path(paths, "protoweft.server.a2a.path", props.getA2a().getPath());
        }
        if (props.getRap().isEnabled() && !"/".equals(props.getRap().getBasePath())) {
            path(paths, "protoweft.server.rap.base-path", props.getRap().getBasePath());
        }
        path(paths, "protoweft.discovery.mcp-path", discovery.getMcpPath());
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
            throw invalid(key, null, "add the io.github.rahulbsw:" + artifact + " dependency to use this protocol");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static InvalidConfigurationPropertyValueException invalid(String key, Object value, String reason) {
        return new InvalidConfigurationPropertyValueException(key, value, reason);
    }
}
