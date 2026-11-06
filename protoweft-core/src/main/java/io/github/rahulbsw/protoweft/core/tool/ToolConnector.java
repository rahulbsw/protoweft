package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ProtocolConnector;

/**
 * A connector that discovers and calls tools on one remote peer. Application code should
 * depend on this interface (or on {@link ToolSource}), not on a protocol-specific class.
 */
public interface ToolConnector extends ProtocolConnector, ToolSource {
}
