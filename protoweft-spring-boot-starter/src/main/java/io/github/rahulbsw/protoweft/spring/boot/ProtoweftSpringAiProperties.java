package io.github.rahulbsw.protoweft.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spring AI integration ({@code protoweft.spring-ai.*}); everything is off unless enabled. */
@ConfigurationProperties(prefix = "protoweft.spring-ai")
public class ProtoweftSpringAiProperties {

    private final ToolCallbacks toolCallbacks = new ToolCallbacks();
    private final ToolSearch toolSearch = new ToolSearch();

    public ToolCallbacks getToolCallbacks() {
        return toolCallbacks;
    }

    public ToolSearch getToolSearch() {
        return toolSearch;
    }

    /** Which tools become Spring AI ToolCallbacks. */
    public enum Expose {
        /** Only the application's own {@code @AgentTool} methods. */
        LOCAL,
        /** Every tool in the ToolRegistry, including remote MCP, UTCP and A2A tools. */
        REGISTRY
    }

    /** Protoweft tools as a Spring AI {@code ToolCallbackProvider} bean. */
    public static class ToolCallbacks {

        /**
         * Whether to register the ToolCallbackProvider bean and add it to the auto-configured
         * ChatClient.Builder. Spring AI's MCP server re-serves every ToolCallbackProvider bean,
         * so this is opt-in.
         */
        private boolean enabled;

        /** Which tools to expose: local tools only (default) or the whole registry. */
        private Expose expose = Expose.LOCAL;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Expose getExpose() {
            return expose;
        }

        public void setExpose(Expose expose) {
            this.expose = expose;
        }
    }

    /** The Protoweft capability catalog as Spring AI's tool-search index. */
    public static class ToolSearch {

        /**
         * Whether to add Spring AI's tool-search advisor, backed by the Protoweft catalog, to the
         * auto-configured ChatClient.Builder. Requires {@code protoweft.discovery.enabled=true}.
         */
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
