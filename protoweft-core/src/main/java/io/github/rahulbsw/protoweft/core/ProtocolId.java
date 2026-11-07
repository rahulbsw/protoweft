package io.github.rahulbsw.protoweft.core;

import java.util.Objects;

/**
 * Identifies an interoperability protocol (for example MCP, A2A or UTCP).
 *
 * <p>This is an open value type rather than an enum so that third-party connectors can
 * introduce protocols without changing the core.
 *
 * @param value the protocol's short, lower-case identifier, for example {@code "mcp"}
 */
public record ProtocolId(String value) {

    /** Model Context Protocol. */
    public static final ProtocolId MCP = new ProtocolId("mcp");

    /** Agent2Agent protocol. */
    public static final ProtocolId A2A = new ProtocolId("a2a");

    /** Universal Tool Calling Protocol. */
    public static final ProtocolId UTCP = new ProtocolId("utcp");

    /** Reactive Agent Protocol (Hydro project / AWS Infinity). */
    public static final ProtocolId RAP = new ProtocolId("rap");

    public ProtocolId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("protocol id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
