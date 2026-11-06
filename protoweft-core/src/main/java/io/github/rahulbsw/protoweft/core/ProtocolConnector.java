package io.github.rahulbsw.protoweft.core;

/**
 * The extension seam every protocol module implements.
 *
 * <p>A connector owns one connection to one peer. It is created by a protocol-specific
 * builder, {@linkplain #start() started} explicitly, and {@linkplain #close() closed} when
 * no longer needed. Implementations must be safe to close more than once.
 */
public interface ProtocolConnector extends AutoCloseable {

    ProtocolId protocol();

    /**
     * Connects to the peer and performs any protocol handshake.
     *
     * @throws ProtoweftException if the connection or handshake fails
     */
    void start();

    /**
     * What this connector implements. Before {@link #start()} the negotiated revision is
     * empty; afterwards it reflects the handshake.
     */
    ComplianceReport compliance();

    @Override
    void close();
}
