package io.agentweave.a2a.internal;

import org.a2aproject.sdk.server.TransportMetadata;
import org.a2aproject.sdk.spec.TransportProtocol;

/**
 * Registers the JSON-RPC transport with a2a-java's {@code ServiceLoader}-based agent-card
 * validation (the SDK only ships this registration in its Quarkus reference modules).
 * Public only because {@code ServiceLoader} requires it; not AgentWeave API.
 */
public final class JsonRpcTransportMetadata implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
