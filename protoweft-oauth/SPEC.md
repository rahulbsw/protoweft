---
id: protoweft-oauth
type: module-design
status: draft
title: protoweft-oauth — OAuth 2.1 client and resource-server support (MCP authorization 2025-11-25)
parent: protoweft-architecture
---

# protoweft-oauth

## Responsibility

Protocol-neutral OAuth 2.1 for HTTP connectors and server endpoints, following the MCP
authorization specification 2025-11-25. **Client:** `OAuthClient` turns a 401/403 challenge into a
bearer token (PRM discovery, AS metadata discovery, registration, grant, refresh, step-up).
**Resource server:** `ProtectedResourceMetadata` + `ProtectedResourceMetadataEndpoint` publish
RFC 9728 metadata; `BearerTokenAuthenticator` (an `InboundAuthenticator`) validates tokens through a
`TokenVerifier` (default `JwtTokenVerifier`). MCP, A2A or UTCP wiring lives in those modules.

## Boundary

- Public API (`io.github.rahulbsw.protoweft.oauth`): `OAuthClient` (+ `Builder`, `Grant`), `Challenge`,
  `TokenResult`, `ClientRegistration` (+ `Builder`), `TokenEndpointAuthMethod`, `TokenStore`
  (+ `Key`), `StoredToken`, `BrowserLauncher`, `AuthorizationCallback`,
  `ProtectedResourceMetadata` (+ `Builder`), `ProtectedResourceMetadataEndpoint`,
  `BearerTokenAuthenticator` (+ `Builder`), `TokenVerifier`, `VerifiedToken`,
  `InvalidTokenException`, `JwtTokenVerifier` (+ `Builder`). Everything else is package-private.
  No Nimbus, servlet or JDK-httpserver type appears in a public signature.
- Depends on `protoweft-core`, `protoweft-server` (`ServerEndpointProvider`), Nimbus
  `oauth2-oidc-sdk` + `nimbus-jose-jwt` (`implementation`), servlet API (`compileOnly`). HTTP is an
  injectable `java.net.http.HttpClient`; the loopback redirect receiver is `com.sun.net.httpserver`.
- Failures are `ProtoweftException`: `AUTHENTICATION` (discovery, registration, grant, policy
  refusals), `CONFIGURATION` (bad builder input), `CONNECTION` (I/O), `PROTOCOL` (bad JSON).
  `handleUnauthorized` never throws for these; it returns `TokenResult.giveUp(reason)`.

## Requirements traceability (MCP authorization 2025-11-25)

| Requirement | Level | Where / how |
|---|---|---|
| Servers implement RFC 9728 PRM with ≥1 `authorization_servers` | MUST | `ProtectedResourceMetadata.Builder.build()` refuses none; `ProtectedResourceMetadataEndpoint` serves it |
| PRM advertised by `WWW-Authenticate resource_metadata` and/or well-known (path-inserted or root) | MUST (one of) | Both: authenticator challenges carry `resource_metadata`; endpoint at path-inserted URI, root optional |
| Server includes `scope` in 401 challenge | SHOULD | Required scopes of the request, else `scopes_supported`, else omitted |
| Client parses `WWW-Authenticate`, responds to 401 | MUST | `Challenge.parse` (multi-challenge, quoted/unquoted), `OAuthClient.handleUnauthorized` |
| Client uses header PRM URL, else path-inserted, else root | MUST | `Discovery.prmCandidates`; a failing header URL also falls back in that order |
| PRM `resource` matches the accessed resource (RFC 9728 §3.3) | MUST (RFC) | `Uris.covers`: same origin and path prefix on a segment boundary, else refused |
| Client supports RFC 8414 and OIDC discovery, in the spec's order (path-aware) | MUST | `Uris.authorizationServerMetadataUris`; issuer must match (RFC 8414 §3.3) |
| AS selection among several `authorization_servers` | client choice | First in listed order that discovers successfully |
| PKCE S256; refuse if `code_challenge_methods_supported` absent (OAuth and OIDC) | MUST | `AuthorizationCodeFlow.requirePkce` before registration or browser; S256 required in the list |
| `resource=<canonical URI>` on authorization and token requests (incl. refresh) | MUST | `AuthorizationCodeFlow` + `TokenRequests`; value is the PRM `resource` in canonical form |
| Canonical URI: lowercase scheme/host, no fragment, no trailing slash; accept uppercase | MUST/SHOULD | `OAuthClient.canonicalResource`; audience comparison canonicalizes both sides |
| Bearer in `Authorization` header on every request, never in query | MUST | API only hands out the token for a header (`cachedToken`); nothing builds query tokens |
| Registration priority: pre-registered → CIMD (if `client_id_metadata_document_supported`) → DCR → prompt | SHOULD | `Registrar`; "prompt" = `AUTHENTICATION` error telling the app to supply a client id |
| CIMD client_id is https with a path; doc includes client_id/client_name/redirect_uris | MUST | URL validated in `ClientRegistration`; hosting the document is the application's job |
| Pre-registered static credentials | SHOULD | `ClientRegistration.clientId/clientSecret/tokenEndpointAuthMethod` |
| DCR (RFC 7591) | MAY | `Registrar.register`; cached per issuer + grant + redirect URI (in memory) |
| Scope: challenge `scope` > PRM `scopes_supported` > omit; challenge authoritative | SHOULD/MUST | `OAuthClient.obtain` |
| Step-up on 403 `insufficient_scope`, bounded retries, tracked per resource+scope | SHOULD | `handleUnauthorized` (status 403), `maxStepUpRetries` (default 2) per (resource, status, scope) in `retryWindow` |
| Invalid/expired token → 401 | MUST | `BearerTokenAuthenticator` → `unauthenticated(…, "Bearer error=\"invalid_token\", …")` |
| Validate token audience is this server (RFC 8707) | MUST | `JwtTokenVerifier` (`aud`) and again in `BearerTokenAuthenticator` for any verifier |
| Validate tokens per OAuth 2.1 §5.2 | MUST | `JwtTokenVerifier`: signature (RS256/ES256, JWKS), `typ` JWT/at+jwt, `exp` required, `nbf`, skew, `iss` allow-list |
| Insufficient scope → 403 `error="insufficient_scope"`, `scope`, `resource_metadata` | SHOULD | `BearerTokenAuthenticator` (scope = required ∪ granted, the "recommended approach") |
| No token passthrough | MUST | Token never stored in `CallerIdentity`, reasons or logs; only verified claims are exposed |
| AS endpoints over HTTPS; redirect URIs localhost or HTTPS | MUST | `Uris.requireSecure` (plain http allowed only for loopback: `127.0.0.0/8`, `::1` or the exact name `localhost`, as browsers' secure-context rule does; the MCP conformance suite's authorization server runs on `http://localhost`); redirect is `http://127.0.0.1:<port>/callback` or a configured https URI |
| `state` used and verified; mismatches discarded | SHOULD | Loopback receiver answers 400 to wrong/missing state and keeps waiting; callback path fails |
| Secure token storage | MUST | `TokenStore` SPI; default in-memory only; `toString` of tokens/secrets masked |
| Refresh token rotation for public clients | AS MUST | AS-side; the client keeps a rotated refresh token, else the old one |
| RFC 9207 `iss` in the authorization response | — (mix-up defence) | Checked when present |
| Client credentials grant | ext-auth extension | `Grant.CLIENT_CREDENTIALS` |
| `private_key_jwt`, DPoP, mTLS, token introspection client, CIMD document hosting | MAY | Not implemented (a custom `TokenVerifier` can do introspection) |

## Decisions

- **One `OAuthClient` per client identity, many resources.** Per canonical resource it keeps the
  PRM, AS metadata, chosen client and token key; flows for one resource are serialized with a lock.
  Tokens are keyed `(resource, issuer, client_id)`, so a token is only ever sent to the resource it
  was issued for. A pre-registered/CIMD identity is known without registering, so a token in a
  persistent `TokenStore` is reused after a restart once discovery has run.
- **`cachedToken` never starts a flow** but refreshes an expired token (30 s margin) when it has a
  refresh token. `accessToken(resource, challenge)` may run the whole flow up front.
- **401 handling.** If the token in use was rejected it is not reused: refresh (when the refresh
  token's scopes cover the challenge) else a full grant. Refresh never widens scope; step-up always
  re-authorizes. Attempts per (resource, status, scope set) are limited (401: `maxStepUpRetries+1`,
  403: `maxStepUpRetries`) within `retryWindow` (default 1 min), since the client cannot observe
  request success.
- **PRM `resource` drives `resource=` and the token key**, not the raw request URL (it may be a
  path prefix of it), matching what the server validates as audience.
- **Loopback redirect** on `127.0.0.1` (never a DNS name), path `/callback`, ephemeral port reused
  across flows while free so DCR registrations stay valid; `loopbackPort` fixes it. HTTPS redirect
  URIs go through the application's `AuthorizationCallback`.
- **Token endpoint auth.** Pre-registered: configured, else basic when a secret exists and the AS
  allows it (RFC 8414 default), else post; `none` without a secret. DCR: the method the AS
  registered; requested `none` for authorization code, basic/post for client credentials. CIMD:
  `none`.
- **Resource-server challenges** (exact): missing token `Bearer resource_metadata="<prm>",
  scope="<s>"`; invalid `Bearer error="invalid_token", resource_metadata="<prm>", scope="<s>"`
  (RFC 6750 §3.1 adds `error` once a token was presented); 403 `Bearer error="insufficient_scope",
  scope="<s>", resource_metadata="<prm>"`. `GET/HEAD/OPTIONS` under
  `/.well-known/oauth-protected-resource` are allowed anonymously because hosts put the same
  authenticator in front of every endpoint.
- **Caller identity**: name = `sub`, else `client_id`/`azp`; attributes `scopes` (sorted
  `List<String>`) and `claims` (`Map`).
- **Nimbus for messages, our code for policy.** Nimbus builds/parses authorization, token, DCR
  and metadata messages, PKCE, and JWT/JWKS processing; `WWW-Authenticate` parsing (Nimbus lacks
  `resource_metadata`), PRM (not in Nimbus), transport and all MCP rules are ours.

## Coverage

Implemented: everything in the traceability table not marked "Not implemented". Not implemented:
`private_key_jwt`, DPoP, mTLS-bound tokens, token introspection/revocation clients, generating or
hosting the CIMD document, persisting DCR registrations across restarts, the 2025-03-26 fallback
to default `/authorize`/`/token` endpoints when no metadata exists, opaque-token verification out
of the box.

## Verification

`./gradlew :protoweft-oauth:build` (offline, loopback only): a Nimbus-signing test AS
(`TestAuthorizationServer`: selectable metadata variant, JWKS, authorize with 302, token with real
S256 verification, refresh, DCR, recorded requests) and a PRM server (`TestResourceServer`).
Covered: each PRM discovery path and fallback order; PRM resource mismatch / empty AS list; AS
discovery order for path and root issuers incl. OIDC fallbacks; issuer mismatch and non-HTTPS
refusal; PKCE refusal; PKCE tampering fails at the token endpoint; `resource` on authorize, code,
refresh and client-credentials requests; scope selection (challenge > scopes_supported > omit);
client credentials; authorization code with a programmatic browser incl. forged-state discard
and RFC 9207 issuer mismatch; HTTPS redirect callback; refresh on expiry and on 401; step-up and
its retry limit; CIMD only when advertised; pre-registration priority; DCR; token auth
basic/post/none; persistent-store reuse; `BearerTokenAuthenticator` valid / expired / skew / nbf /
no exp / wrong audience / wrong issuer / bad signature / malformed / insufficient scope with exact
challenge strings / custom verifier audience / ES256 / remote JWKS; PRM JSON; and an end-to-end
Jetty test (`ProtoweftServer` + `InboundAuthFilter`) where `OAuthClient` discovers, authorizes and
steps up against the real filter.

## Sources

- MCP authorization 2025-11-25: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- RFC 9728 (Protected Resource Metadata), RFC 8414 (AS Metadata), RFC 8707 (Resource Indicators),
  RFC 7591 (DCR), RFC 6750 (Bearer), RFC 8252 §7.3 (loopback redirect), RFC 9207 (`iss`),
  RFC 9068 (JWT access tokens), OAuth 2.1 draft-ietf-oauth-v2-1-13,
  draft-ietf-oauth-client-id-metadata-document-00.
- Nimbus `oauth2-oidc-sdk` 11.39 and `nimbus-jose-jwt` 10.10 APIs (verified against the jars).
