package io.agentweave.core;

/**
 * Protocol-neutral classification of failures. Connectors map protocol-specific errors
 * (for example JSON-RPC error codes) onto these categories.
 */
public enum ErrorCategory {
    /** The connector was misconfigured or used before it was started. */
    CONFIGURATION,
    /** The transport could not be established or was lost. */
    CONNECTION,
    /** Credentials were missing, rejected, or not usable with the chosen transport. */
    AUTHENTICATION,
    /** The peer violated the protocol or returned an unexpected protocol-level error. */
    PROTOCOL,
    /** No peer and client protocol revision could be agreed on. */
    VERSION_MISMATCH,
    /** The requested tool does not exist on the peer. */
    TOOL_NOT_FOUND,
    /** The peer rejected the supplied arguments. */
    INVALID_ARGUMENTS,
    /** The operation did not complete within the configured timeout. */
    TIMEOUT,
    /** The feature is not implemented by this connector or not offered by the peer. */
    UNSUPPORTED,
    /** Any other failure. */
    INTERNAL
}
