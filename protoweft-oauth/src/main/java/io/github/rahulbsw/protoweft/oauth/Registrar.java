package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.client.ClientInformation;
import com.nimbusds.oauth2.sdk.client.ClientMetadata;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationErrorResponse;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationRequest;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Chooses or obtains a client identity: pre-registered, then CIMD, then Dynamic Client Registration. */
final class Registrar {

    /** The client identity used at one authorization server. {@code toString} hides the secret. */
    record RegisteredClient(String clientId, String secret, TokenEndpointAuthMethod method, String source) {
        @Override
        public String toString() {
            return "RegisteredClient[" + clientId + ", " + method.value() + ", " + source + "]";
        }
    }

    private final Http http;
    private final ClientRegistration config;
    private final Map<String, RegisteredClient> dynamic = new ConcurrentHashMap<>();

    Registrar(Http http, ClientRegistration config) {
        this.http = http;
        this.config = config;
    }

    /** The pre-registered or CIMD identity for this server, if one applies; never touches the network. */
    RegisteredClient known(AuthorizationServerMetadata as, OAuthClient.Grant grant) {
        if (config.clientId().isPresent()) {
            String secret = config.clientSecret().orElse(null);
            TokenEndpointAuthMethod method = config.authMethod().orElseGet(() -> chooseMethod(secret, as));
            return new RegisteredClient(config.clientId().get(), secret, method, "pre-registered");
        }
        if (grant == OAuthClient.Grant.AUTHORIZATION_CODE && config.metadataDocument().isPresent()
                && Boolean.TRUE.equals(as.getCustomParameter("client_id_metadata_document_supported"))) {
            return new RegisteredClient(config.metadataDocument().get().toString(), null,
                    TokenEndpointAuthMethod.NONE, "client-id-metadata-document");
        }
        return null;
    }

    RegisteredClient clientFor(AuthorizationServerMetadata as, OAuthClient.Grant grant, URI redirectUri) {
        RegisteredClient known = known(as, grant);
        if (known != null) {
            return known;
        }
        if (config.dynamicEnabled() && as.getRegistrationEndpointURI() != null) {
            String key = as.getIssuer().getValue() + " " + grant + " " + redirectUri;
            RegisteredClient cached = dynamic.get(key);
            if (cached != null) {
                return cached;
            }
            RegisteredClient registered = register(as, grant, redirectUri);
            dynamic.put(key, registered);
            return registered;
        }
        throw Uris.auth("no way to register a client with " + as.getIssuer()
                + ": configure a pre-registered client id"
                + (config.metadataDocument().isPresent() ? " (server does not support client ID metadata documents)" : "")
                + (as.getRegistrationEndpointURI() == null ? " (server has no registration_endpoint)" : ""));
    }

    private RegisteredClient register(AuthorizationServerMetadata as, OAuthClient.Grant grant, URI redirectUri) {
        URI endpoint = Uris.requireSecure(as.getRegistrationEndpointURI(), "registration_endpoint");
        ClientMetadata metadata = new ClientMetadata();
        metadata.setName(config.clientName());
        TokenEndpointAuthMethod requested;
        if (grant == OAuthClient.Grant.AUTHORIZATION_CODE) {
            metadata.setRedirectionURIs(Set.of(redirectUri));
            metadata.setGrantTypes(Set.of(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN));
            metadata.setResponseTypes(Set.of(ResponseType.CODE));
            requested = config.dynamicAuthMethod().orElse(TokenEndpointAuthMethod.NONE);
        } else {
            metadata.setGrantTypes(Set.of(GrantType.CLIENT_CREDENTIALS));
            requested = config.dynamicAuthMethod().orElseGet(() -> chooseMethod("secret", as));
        }
        metadata.setTokenEndpointAuthMethod(new ClientAuthenticationMethod(requested.value()));
        config.extraMetadata().forEach(metadata::setCustomField);

        ClientRegistrationResponse response;
        try {
            response = ClientRegistrationResponse.parse(
                    http.send(new ClientRegistrationRequest(endpoint, metadata, null).toHTTPRequest()));
        } catch (ParseException e) {
            throw Uris.auth("invalid dynamic client registration response: " + e.getMessage(), e);
        }
        if (!response.indicatesSuccess()) {
            ClientRegistrationErrorResponse error = response.toErrorResponse();
            throw Uris.auth("dynamic client registration failed: " + describe(error.getErrorObject()));
        }
        ClientInformation info = response.toSuccessResponse().getClientInformation();
        String secret = info.getSecret() == null ? null : info.getSecret().getValue();
        ClientAuthenticationMethod registered = info.getMetadata().getTokenEndpointAuthMethod();
        TokenEndpointAuthMethod method = registered == null ? null : TokenEndpointAuthMethod.fromValue(registered.getValue());
        if (method == null || (method != TokenEndpointAuthMethod.NONE && secret == null)) {
            method = chooseMethod(secret, as);
        }
        return new RegisteredClient(info.getID().getValue(), secret, method, "dynamic");
    }

    /** With a secret: basic when allowed (the RFC 8414 default), else post. Without: none. */
    static TokenEndpointAuthMethod chooseMethod(String secret, AuthorizationServerMetadata as) {
        if (secret == null) {
            return TokenEndpointAuthMethod.NONE;
        }
        List<ClientAuthenticationMethod> supported = as.getTokenEndpointAuthMethods();
        if (supported == null || supported.isEmpty() || supported.contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)) {
            return TokenEndpointAuthMethod.CLIENT_SECRET_BASIC;
        }
        if (supported.contains(ClientAuthenticationMethod.CLIENT_SECRET_POST)) {
            return TokenEndpointAuthMethod.CLIENT_SECRET_POST;
        }
        return TokenEndpointAuthMethod.CLIENT_SECRET_BASIC;
    }

    static String describe(com.nimbusds.oauth2.sdk.ErrorObject error) {
        if (error == null) {
            return "unknown error";
        }
        String code = error.getCode() == null ? "error" : error.getCode();
        String status = error.getHTTPStatusCode() > 0 ? " (HTTP " + error.getHTTPStatusCode() + ")" : "";
        return error.getDescription() == null ? code + status : code + ": " + error.getDescription() + status;
    }
}
