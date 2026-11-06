package io.github.rahulbsw.protoweft.oauth;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How {@link OAuthClient} identifies itself to an authorization server. The options are tried in
 * the MCP 2025-11-25 priority order:
 * <ol>
 *   <li>a pre-registered client ({@link Builder#clientId}), used with every authorization server;</li>
 *   <li>a Client ID Metadata Document URL ({@link Builder#clientIdMetadataDocument}), used only when
 *       the server advertises {@code client_id_metadata_document_supported: true};</li>
 *   <li>Dynamic Client Registration (RFC 7591), when the server has a {@code registration_endpoint}
 *       and {@link Builder#dynamicRegistration} is on (the default).</li>
 * </ol>
 * With none applicable the client fails with {@code AUTHENTICATION}; the application should then
 * ask the user for client details (MCP option 4).
 */
public final class ClientRegistration {

    private final String clientId;
    private final String clientSecret;
    private final TokenEndpointAuthMethod authMethod;
    private final URI metadataDocument;
    private final boolean dynamic;
    private final String clientName;
    private final TokenEndpointAuthMethod dynamicAuthMethod;
    private final Map<String, Object> extraMetadata;

    private ClientRegistration(Builder b) {
        this.clientId = b.clientId;
        this.clientSecret = b.clientSecret;
        this.authMethod = b.authMethod;
        this.metadataDocument = b.metadataDocument;
        this.dynamic = b.dynamic;
        this.clientName = b.clientName;
        this.dynamicAuthMethod = b.dynamicAuthMethod;
        this.extraMetadata = Map.copyOf(b.extraMetadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Dynamic registration only (with the default client name). */
    public static ClientRegistration dynamic() {
        return builder().build();
    }

    Optional<String> clientId() {
        return Optional.ofNullable(clientId);
    }

    Optional<String> clientSecret() {
        return Optional.ofNullable(clientSecret);
    }

    Optional<TokenEndpointAuthMethod> authMethod() {
        return Optional.ofNullable(authMethod);
    }

    Optional<URI> metadataDocument() {
        return Optional.ofNullable(metadataDocument);
    }

    boolean dynamicEnabled() {
        return dynamic;
    }

    String clientName() {
        return clientName;
    }

    Optional<TokenEndpointAuthMethod> dynamicAuthMethod() {
        return Optional.ofNullable(dynamicAuthMethod);
    }

    Map<String, Object> extraMetadata() {
        return extraMetadata;
    }

    @Override
    public String toString() {
        return "ClientRegistration[clientId=" + clientId + ", secret=" + (clientSecret == null ? "none" : "***")
                + ", cimd=" + metadataDocument + ", dynamic=" + dynamic + "]";
    }

    public static final class Builder {
        private String clientId;
        private String clientSecret;
        private TokenEndpointAuthMethod authMethod;
        private URI metadataDocument;
        private boolean dynamic = true;
        private String clientName = "Protoweft";
        private TokenEndpointAuthMethod dynamicAuthMethod;
        private final Map<String, Object> extraMetadata = new LinkedHashMap<>();

        private Builder() {
        }

        /** A client id registered out of band with the authorization server. */
        public Builder clientId(String clientId) {
            this.clientId = Objects.requireNonNull(clientId, "clientId");
            return this;
        }

        /** The pre-registered client's secret (confidential clients). */
        public Builder clientSecret(String clientSecret) {
            this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
            return this;
        }

        /**
         * The pre-registered client's token endpoint authentication. Defaults to
         * {@code client_secret_basic} when a secret is set and the server allows it (else
         * {@code client_secret_post}), and {@code none} without a secret.
         */
        public Builder tokenEndpointAuthMethod(TokenEndpointAuthMethod method) {
            this.authMethod = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * The HTTPS URL of this client's Client ID Metadata Document; it becomes the
         * {@code client_id}. The document itself must be hosted by the application, list the
         * redirect URI in use, and carry {@code client_id} equal to this URL.
         */
        public Builder clientIdMetadataDocument(URI url) {
            Objects.requireNonNull(url, "url");
            String path = url.getRawPath();
            // draft-ietf-oauth-client-id-metadata-document-00 §3: https, a path without dot
            // segments, no fragment, no user info.
            if (!"https".equalsIgnoreCase(url.getScheme()) || path == null || path.isEmpty() || path.equals("/")
                    || url.getRawFragment() != null || url.getRawUserInfo() != null
                    || ("/" + path + "/").contains("/./") || ("/" + path + "/").contains("/../")) {
                throw Uris.config("client ID metadata document URL must be https with a path: " + url);
            }
            this.metadataDocument = url;
            return this;
        }

        /** Whether to fall back to Dynamic Client Registration (default {@code true}). */
        public Builder dynamicRegistration(boolean enabled) {
            this.dynamic = enabled;
            return this;
        }

        /** {@code client_name} sent in dynamic registration. */
        public Builder clientName(String clientName) {
            this.clientName = Objects.requireNonNull(clientName, "clientName");
            return this;
        }

        /** The {@code token_endpoint_auth_method} to request in dynamic registration. */
        public Builder dynamicRegistrationAuthMethod(TokenEndpointAuthMethod method) {
            this.dynamicAuthMethod = Objects.requireNonNull(method, "method");
            return this;
        }

        /** Extra RFC 7591 client metadata for dynamic registration, for example {@code software_id}. */
        public Builder clientMetadata(String name, Object value) {
            extraMetadata.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public ClientRegistration build() {
            if (clientSecret != null && clientId == null) {
                throw Uris.config("a client secret needs a client id");
            }
            return new ClientRegistration(this);
        }
    }
}
