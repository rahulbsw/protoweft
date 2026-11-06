package io.github.rahulbsw.protoweft.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-demand tool discovery ({@code protoweft-discovery}): a capability catalog over every source
 * of the tool registry, optionally served as MCP meta-tools.
 */
@ConfigurationProperties("protoweft.discovery")
public class ProtoweftDiscoveryProperties {

    /** Fallback on low-confidence searches; mirrors the discovery module's {@code FallbackPolicy.Mode}. */
    public enum FallbackMode {
        /** Broaden the search, then add a names-only list of further candidates. */
        PROGRESSIVE,
        /** Return every permitted, available capability with its schema (the all-tools baseline). */
        ALL_SCHEMAS,
        /** Only report the low confidence. */
        NONE
    }

    /** Whether a CapabilityCatalog bean is built over the tool registry (needs protoweft-discovery). */
    private boolean enabled;

    /** What a search does when its confidence is low. */
    private FallbackMode fallback = FallbackMode.PROGRESSIVE;

    /** Per registry source (local tools or a declared connector): how the catalog treats it. */
    private Map<String, Source> sources = new LinkedHashMap<>();

    /**
     * Whether the discovery meta-tools (search_capabilities, describe_capabilities,
     * call_capability) are served over MCP, behind the inbound authenticator (needs protoweft-mcp).
     */
    private boolean exposeMcp;

    /** Path of the discovery MCP endpoint. */
    private String mcpPath = "/mcp-discovery";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public FallbackMode getFallback() {
        return fallback;
    }

    public void setFallback(FallbackMode fallback) {
        this.fallback = fallback;
    }

    public Map<String, Source> getSources() {
        return sources;
    }

    public void setSources(Map<String, Source> sources) {
        this.sources = sources;
    }

    public boolean isExposeMcp() {
        return exposeMcp;
    }

    public void setExposeMcp(boolean exposeMcp) {
        this.exposeMcp = exposeMcp;
    }

    public String getMcpPath() {
        return mcpPath;
    }

    public void setMcpPath(String mcpPath) {
        this.mcpPath = mcpPath;
    }

    /** Catalog policy for one source. */
    public static class Source {

        /**
         * Scopes a caller must hold (caller attribute "scopes") to see or call any of the source's
         * capabilities. Empty means visible to everyone.
         */
        private List<String> requiredScopes = new ArrayList<>();

        public List<String> getRequiredScopes() {
            return requiredScopes;
        }

        public void setRequiredScopes(List<String> requiredScopes) {
            this.requiredScopes = requiredScopes;
        }
    }
}
