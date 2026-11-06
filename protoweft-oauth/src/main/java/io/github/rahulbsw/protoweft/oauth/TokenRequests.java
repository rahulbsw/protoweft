package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.AccessTokenType;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.token.Tokens;

import java.net.URI;
import java.time.Clock;
import java.util.Set;

/** Token endpoint requests (RFC 6749 §4/§6, OAuth 2.1 §3.2) with the RFC 8707 {@code resource} parameter. */
final class TokenRequests {

    private final Http http;
    private final Clock clock;

    TokenRequests(Http http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    /**
     * Sends a token request and maps the reply.
     *
     * @param scope           requested scope (space-delimited) or {@code null} to omit it
     * @param previousRefresh the refresh token to keep when the server does not rotate it, or {@code null}
     * @param previousScopes  the scopes to assume when the reply has none and no scope was requested
     */
    StoredToken request(AuthorizationServerMetadata as, Registrar.RegisteredClient client, AuthorizationGrant grant,
                        String scope, URI resource, String previousRefresh, Set<String> previousScopes) {
        URI endpoint = Uris.requireSecure(as.getTokenEndpointURI(), "token_endpoint");
        ClientID clientId = new ClientID(client.clientId());
        TokenRequest.Builder builder = switch (client.method()) {
            case CLIENT_SECRET_BASIC -> new TokenRequest.Builder(endpoint,
                    new ClientSecretBasic(clientId, new Secret(requireSecret(client))), grant);
            case CLIENT_SECRET_POST -> new TokenRequest.Builder(endpoint,
                    new ClientSecretPost(clientId, new Secret(requireSecret(client))), grant);
            case NONE -> new TokenRequest.Builder(endpoint, clientId, grant);
        };
        if (scope != null) {
            builder.scope(Scope.parse(scope));
        }
        builder.resource(resource);

        TokenResponse response;
        try {
            response = TokenResponse.parse(http.send(builder.build().toHTTPRequest()));
        } catch (ParseException e) {
            throw Uris.auth("invalid token response from " + endpoint + ": " + e.getMessage(), e);
        }
        if (!response.indicatesSuccess()) {
            throw Uris.auth("token request to " + endpoint + " failed: "
                    + Registrar.describe(response.toErrorResponse().getErrorObject()));
        }
        Tokens tokens = ((AccessTokenResponse) response).getTokens();
        AccessToken access = tokens.getAccessToken();
        if (!AccessTokenType.BEARER.equals(access.getType())) {
            throw Uris.auth("token endpoint issued a " + access.getType() + " token; only Bearer is supported");
        }
        RefreshToken refresh = tokens.getRefreshToken();
        Set<String> granted = access.getScope() != null ? Set.copyOf(access.getScope().toStringList())
                : scope != null ? StoredToken.split(scope)
                : previousScopes == null ? Set.of() : previousScopes;
        return new StoredToken(access.getValue(),
                refresh != null ? refresh.getValue() : previousRefresh,
                access.getLifetime() > 0 ? clock.instant().plusSeconds(access.getLifetime()) : null,
                granted);
    }

    private static String requireSecret(Registrar.RegisteredClient client) {
        if (client.secret() == null) {
            throw Uris.config("token endpoint auth method " + client.method().value() + " needs a client secret");
        }
        return client.secret();
    }
}
