package io.agentweave.core;

import java.util.Objects;
import java.util.Optional;

/**
 * The single unchecked exception type thrown by AgentWeave APIs.
 *
 * <p>A tool that runs and reports a failure is <em>not</em> an exception: it is a
 * {@link io.agentweave.core.tool.ToolOutcome} with {@code error} set. Exceptions signal that the
 * call itself could not be carried out.
 */
public class AgentWeaveException extends RuntimeException {

    private final ErrorCategory category;
    private final ProtocolId protocol;
    private final String protocolCode;

    public AgentWeaveException(ErrorCategory category, ProtocolId protocol, String message) {
        this(category, protocol, null, message, null);
    }

    public AgentWeaveException(ErrorCategory category, ProtocolId protocol, String message, Throwable cause) {
        this(category, protocol, null, message, cause);
    }

    /**
     * @param category     neutral classification, never {@code null}
     * @param protocol     protocol the failure originated from, or {@code null} if none
     * @param protocolCode the protocol's own error code (for example a JSON-RPC code), or
     *                     {@code null}
     * @param message      human-readable description
     * @param cause        underlying cause, or {@code null}
     */
    public AgentWeaveException(ErrorCategory category, ProtocolId protocol, String protocolCode,
                               String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category");
        this.protocol = protocol;
        this.protocolCode = protocolCode;
    }

    public ErrorCategory category() {
        return category;
    }

    public Optional<ProtocolId> protocol() {
        return Optional.ofNullable(protocol);
    }

    /** The protocol-native error code, if the failure carried one. */
    public Optional<String> protocolCode() {
        return Optional.ofNullable(protocolCode);
    }
}
