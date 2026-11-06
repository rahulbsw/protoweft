package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.AuthorizationResponse;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.net.URI;
import java.util.List;
import java.util.function.BiFunction;

/** The OAuth 2.1 authorization code grant with PKCE S256, {@code state} and {@code resource} (RFC 8707). */
final class AuthorizationCodeFlow {

    private final TokenRequests tokens;

    AuthorizationCodeFlow(TokenRequests tokens) {
        this.tokens = tokens;
    }

    /**
     * MCP 2025-11-25 "Authorization Code Protection": the client MUST refuse to proceed when the
     * metadata lacks {@code code_challenge_methods_supported}, and uses S256.
     */
    static void requirePkce(AuthorizationServerMetadata as) {
        List<CodeChallengeMethod> methods = as.getCodeChallengeMethods();
        if (methods == null || methods.isEmpty()) {
            throw Uris.auth("authorization server " + as.getIssuer()
                    + " does not advertise code_challenge_methods_supported; refusing to proceed without PKCE");
        }
        if (!methods.contains(CodeChallengeMethod.S256)) {
            throw Uris.auth("authorization server " + as.getIssuer() + " does not support PKCE S256");
        }
    }

    /**
     * Runs the flow.
     *
     * @param userAgent given the authorization request URI and the expected state, returns the
     *                  redirect URI the user agent came back to
     */
    StoredToken run(AuthorizationServerMetadata as, Registrar.RegisteredClient client, String scope, URI resource,
                    URI redirectUri, BiFunction<URI, String, URI> userAgent) {
        requirePkce(as);
        URI endpoint = Uris.requireSecure(as.getAuthorizationEndpointURI(), "authorization_endpoint");
        CodeVerifier verifier = new CodeVerifier();
        State state = new State();
        AuthorizationRequest.Builder request = new AuthorizationRequest.Builder(ResponseType.CODE,
                new ClientID(client.clientId()))
                .endpointURI(endpoint)
                .redirectionURI(redirectUri)
                .state(state)
                .codeChallenge(verifier, CodeChallengeMethod.S256)
                .resource(resource);
        if (scope != null) {
            request.scope(Scope.parse(scope));
        }
        URI callback = userAgent.apply(request.build().toURI(), state.getValue());

        AuthorizationResponse response;
        try {
            response = AuthorizationResponse.parse(callback);
        } catch (ParseException e) {
            throw Uris.auth("invalid authorization response: " + e.getMessage(), e);
        }
        if (response.getState() == null || !state.equals(response.getState())) {
            throw Uris.auth("authorization response state does not match the request; discarded");
        }
        // RFC 9207: when the server names itself in the response, it must be the server we asked.
        if (response.getIssuer() != null && !response.getIssuer().equals(as.getIssuer())) {
            throw Uris.auth("authorization response came from issuer " + response.getIssuer()
                    + ", expected " + as.getIssuer());
        }
        if (!response.indicatesSuccess()) {
            AuthorizationErrorResponse error = response.toErrorResponse();
            throw Uris.auth("authorization denied: " + Registrar.describe(error.getErrorObject()));
        }
        AuthorizationCode code = response.toSuccessResponse().getAuthorizationCode();
        if (code == null) {
            throw Uris.auth("authorization response carries no code");
        }
        return tokens.request(as, client, new AuthorizationCodeGrant(code, redirectUri, verifier), scope, resource,
                null, null);
    }

    /** Wraps an application callback for HTTPS redirect URIs. */
    static BiFunction<URI, String, URI> viaCallback(AuthorizationCallback callback) {
        return (request, state) -> {
            try {
                return callback.authorize(request);
            } catch (ProtoweftException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Uris.auth("interrupted during authorization", e);
            } catch (Exception e) {
                throw Uris.auth("authorization callback failed: " + e.getMessage(), e);
            }
        };
    }
}
