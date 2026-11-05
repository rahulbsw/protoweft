package io.agentweave.core.auth;

import io.agentweave.core.ProtocolId;

import java.util.List;

/**
 * Supplies credentials to a connector when it connects. Implement this to plug in a
 * secret store, token cache, or framework-managed credentials.
 */
@FunctionalInterface
public interface CredentialProvider {

    /** A provider that supplies nothing. */
    CredentialProvider NONE = (protocol, target) -> List.of();

    /**
     * @param protocol the protocol requesting credentials
     * @param target   the peer being connected to, as the connector identifies it (for
     *                 example a command line or URL)
     * @return credentials to present; empty for none
     */
    List<Credential> credentialsFor(ProtocolId protocol, String target);
}
