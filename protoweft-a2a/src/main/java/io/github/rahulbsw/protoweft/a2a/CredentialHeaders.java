package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds Protoweft credentials as HTTP headers to every JSON-RPC call. The SDK's own
 * {@code AuthInterceptor} only acts on schemes the card declares, so it is not used.
 */
final class CredentialHeaders extends ClientCallInterceptor {

    private final CredentialProvider credentials;
    private final String target;

    CredentialHeaders(CredentialProvider credentials, String target) {
        this.credentials = credentials;
        this.target = target;
    }

    /** Resolves the provider's credentials into headers: bearer → {@code Authorization}, named secret → its header. */
    static Map<String, String> resolve(CredentialProvider credentials, String target) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Credential credential : credentials.credentialsFor(ProtocolId.A2A, target)) {
            if (credential instanceof Credential.BearerToken bearer) {
                headers.put("Authorization", "Bearer " + bearer.token());
            } else if (credential instanceof Credential.NamedSecret secret) {
                headers.put(secret.name(), secret.value());
            }
        }
        return headers;
    }

    @Override
    public PayloadAndHeaders intercept(String methodName, Object payload, Map<String, String> headers,
                                       AgentCard agentCard, ClientCallContext clientCallContext) {
        Map<String, String> merged = new HashMap<>(headers == null ? Map.of() : headers);
        merged.putAll(resolve(credentials, target));
        return new PayloadAndHeaders(payload, merged);
    }
}
