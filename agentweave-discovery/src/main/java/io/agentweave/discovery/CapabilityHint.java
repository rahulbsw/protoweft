package io.agentweave.discovery;

import io.agentweave.core.tool.ToolSpec;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Behavior hints a capability declares (from MCP tool annotations or {@code @AgentTool}). */
public enum CapabilityHint {
    READ_ONLY(ToolSpec.META_READ_ONLY),
    DESTRUCTIVE(ToolSpec.META_DESTRUCTIVE),
    IDEMPOTENT(ToolSpec.META_IDEMPOTENT),
    OPEN_WORLD(ToolSpec.META_OPEN_WORLD);

    private final String metadataKey;

    CapabilityHint(String metadataKey) {
        this.metadataKey = metadataKey;
    }

    /** Hints whose metadata value is {@code true}; absent or {@code false} means not declared. */
    static Set<CapabilityHint> from(Map<String, Object> metadata) {
        Set<CapabilityHint> hints = EnumSet.noneOf(CapabilityHint.class);
        for (CapabilityHint hint : values()) {
            if (Boolean.TRUE.equals(metadata.get(hint.metadataKey))) {
                hints.add(hint);
            }
        }
        return hints;
    }
}
