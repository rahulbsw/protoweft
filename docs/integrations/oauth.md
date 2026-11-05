# OAuth 2.1

`agentweave-oauth` implements OAuth 2.1 for AgentWeave's HTTP connectors and server endpoints,
following the [MCP authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
(revision `2025-11-25`). It has two halves:

- **Client:** `OAuthClient` turns a server's 401 or 403 challenge into a bearer token: protected
  resource metadata discovery, authorization server discovery, client registration, the grant,
  refresh and step-up. It implements the core `HttpAuthorizer` interface, which the
  [MCP client](../protocols/mcp.md#authorization) accepts.
- **Resource server:** `ProtectedResourceMetadata` and `ProtectedResourceMetadataEndpoint` publish
  RFC 9728 metadata, and `BearerTokenAuthenticator`, an `InboundAuthenticator`, validates bearer
  tokens with a `TokenVerifier` (by default `JwtTokenVerifier`).

```kotlin
dependencies {
    implementation(platform("io.agentweave:agentweave-bom:0.1.0-SNAPSHOT"))
    implementation("io.agentweave:agentweave-oauth")
}
```

It is built on Nimbus `oauth2-oidc-sdk` 11.39 and `nimbus-jose-jwt` 10.10 as internal
(`implementation`) dependencies: no Nimbus type appears in its API. Message building and parsing,
PKCE and JWT/JWKS processing come from Nimbus; `WWW-Authenticate` parsing, protected resource
metadata and the MCP rules are AgentWeave's own.

!!! note "Where it plugs in"
    On the client side, only the MCP Streamable HTTP connector takes an `HttpAuthorizer` today
    (`McpClientConnector.http(..).authorizer(..)`); the A2A and UTCP connectors use static
    [credentials](../concepts/security.md#outbound-credentialprovider). On the server side,
    `BearerTokenAuthenticator` is an ordinary `InboundAuthenticator`, so it protects every endpoint
    of a host: MCP, A2A and UTCP alike.

## Client: `OAuthClient`

```java
// Machine to machine: client credentials with a pre-registered confidential client
OAuthClient oauth = OAuthClient.builder()
        .grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
        .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
        .build();

try (McpClientConnector mcp = McpClientConnector.http(baseUrl).authorizer(oauth).build()) {
    mcp.start();   // 401 -> protected-resource metadata -> AS metadata -> token -> retry
    mcp.invoke(ToolInvocation.of("whoami", Map.of()));
}
```

```java
// Interactive: authorization code with PKCE, the user signs in in a browser
OAuthClient oauth = OAuthClient.builder()                      // AUTHORIZATION_CODE is the default grant
        .registration(ClientRegistration.builder()
                .clientIdMetadataDocument(URI.create("https://app.example.com/oauth/client.json"))
                .build())                                    // else Dynamic Client Registration
        .build();
```

One `OAuthClient` serves one client identity and any number of resources. It is thread-safe: flows
for one resource are serialized, different resources proceed in parallel.

### What happens on a challenge

1. **Parse** the `WWW-Authenticate` header (several challenges, quoted or unquoted values).
2. **Protected resource metadata** (RFC 9728): the `resource_metadata` URL from the header, else the
   path-inserted well-known URI, else the root one. A failing header URL falls back in that order.
   The metadata's `resource` must cover the URL being accessed (same origin and a path prefix on a
   segment boundary), else the flow is refused.
3. **Authorization server metadata** (RFC 8414, then OpenID Connect discovery, path-aware, in the
   order MCP specifies). The issuer must match, and authorization server endpoints must be HTTPS.
   With several `authorization_servers`, the first that discovers successfully is used.
4. **Register** the client (see [below](#client-registration)).
5. **Obtain a token** with `resource=<canonical resource URI>` (RFC 8707) on authorization, token
   and refresh requests. Scope: the challenge's `scope`, else the metadata's `scopes_supported`,
   else none.
6. The connector **retries** with `Authorization: Bearer <token>`. Tokens never go into a query
   string.

### Grants

| `OAuthClient.Grant` | For | Notes |
|---|---|---|
| `AUTHORIZATION_CODE` (default) | A user authorizes in a browser | PKCE S256 and `state` are always used. The flow is refused if the server's metadata doesn't list `S256` in `code_challenge_methods_supported`. An RFC 9207 `iss` in the response is checked when present. |
| `CLIENT_CREDENTIALS` | Machine to machine | Needs a confidential client (a client secret). |

### Client registration

`ClientRegistration` says how the client identifies itself. The options are tried in MCP's priority
order:

1. **Pre-registered:** `clientId(...)`, with `clientSecret(...)` for a confidential client. Used with
   every authorization server.
2. **Client ID Metadata Document:** `clientIdMetadataDocument(httpsUrl)`. The URL becomes the
   `client_id`, used only when the server advertises `client_id_metadata_document_supported`. You
   host the document yourself; it must list the redirect URI in use and carry `client_id` equal to
   the URL.
3. **Dynamic Client Registration** (RFC 7591), when the server has a `registration_endpoint` and
   `dynamicRegistration(true)` (the default). `clientName(...)` (default `AgentWeave`),
   `dynamicRegistrationAuthMethod(...)` and `clientMetadata(name, value)` shape the request.
   Registrations are cached in memory per issuer, grant and redirect URI.

With none applicable, the flow fails with `AUTHENTICATION`; the application should then ask the
user for client details. `ClientRegistration.dynamic()` is DCR only, and the builder's default.

**Token endpoint authentication** (`TokenEndpointAuthMethod`: `CLIENT_SECRET_BASIC`,
`CLIENT_SECRET_POST`, `NONE`):

- Pre-registered: `tokenEndpointAuthMethod(...)` if set; else `client_secret_basic` when a secret
  exists and the server allows it, `client_secret_post` otherwise; `none` without a secret.
- Dynamic registration: the method the server registered (the client asks for `none` with the
  authorization code grant, and basic or post with client credentials).
- Client ID Metadata Document: `none`.

### The browser and the redirect

- **Loopback receiver (default).** The redirect URI is `http://127.0.0.1:<port>/callback`, never a
  DNS name. The port is ephemeral and reused across flows while it stays free, so dynamic
  registrations stay valid; `loopbackPort(port)` fixes it, for a redirect URI pre-registered with a
  port. A callback with a missing or wrong `state` gets HTTP 400 and the receiver keeps waiting.
- **`BrowserLauncher`** opens the authorization URL. The default, `BrowserLauncher.system()`, uses
  the desktop browser when one is available and otherwise prints the URL to standard error.
  Supply your own, for example to show the URL in your UI.
- **HTTPS redirect.** `redirectUri(httpsUri, callback)` replaces the loopback receiver: your
  `AuthorizationCallback` sends the user to the authorization URL and returns the full redirect URI
  it came back with. `OAuthClient` still verifies `state`.
- `authorizationTimeout(duration)` (default 5 minutes) bounds the wait for the user.

### Tokens, refresh and step-up

- **`TokenStore`** keeps tokens, keyed by `TokenStore.Key(resource, authorizationServer, clientId)`,
  so a token is only ever sent to the resource it was issued for. The default,
  `TokenStore.inMemory()`, lasts for the life of the process. Implement `load`, `save` and `remove`
  to persist tokens, and protect them at rest: MCP requires secure token storage. With a
  pre-registered or metadata-document client, a persisted token is reused after a restart once
  discovery has run.
- **Refresh.** An expired token (with a 30-second margin) is refreshed with its refresh token
  before a request. A rotated refresh token replaces the old one. A refresh never widens scope.
- **401.** A token the server rejected isn't reused: the client refreshes (if the refresh token's
  scopes cover the challenge), otherwise runs the grant again.
- **Step-up.** A 403 with `error="insufficient_scope"` re-authorizes with the challenged scope. A
  403 without it isn't treated as an authorization problem.
- **Bounded retries.** Attempts per resource, status and scope set are limited within
  `retryWindow` (default 1 minute): `maxStepUpRetries` (default 2) for 403, one more for 401. The
  client can't observe whether a retried request succeeded, so this prevents endless loops.

### Builder reference

| Method | Default | Meaning |
|---|---|---|
| `grant(Grant)` | `AUTHORIZATION_CODE` | The grant used to obtain tokens. |
| `registration(ClientRegistration)` | `ClientRegistration.dynamic()` | How the client identifies itself. |
| `tokenStore(TokenStore)` | `TokenStore.inMemory()` | Where tokens are kept. |
| `browserLauncher(BrowserLauncher)` | `BrowserLauncher.system()` | Opens the authorization URL. |
| `loopbackPort(int)` | 0 (ephemeral) | Fixed port for the loopback redirect URI. |
| `redirectUri(URI, AuthorizationCallback)` | — | An HTTPS redirect handled by your application. |
| `authorizationTimeout(Duration)` | 5 min | Time the user has to complete the browser flow. |
| `maxStepUpRetries(int)` | 2 | Step-up re-authorizations per resource and scope set within the window. |
| `retryWindow(Duration)` | 1 min | Attempts further apart start a fresh count. |
| `httpClient(HttpClient)` | a default client | For metadata, registration and token requests; must not follow redirects. |
| `requestTimeout(Duration)` | 30 s | Timeout of those requests. |

### Using it without a connector

`OAuthClient` doesn't depend on MCP. Any HTTP code can call it:

- `cachedToken(resource)`: a token already held, refreshed if expired. Never starts discovery or a
  browser flow.
- `accessToken(resource, challenge)`: runs the whole flow up front, with a `Challenge` (for
  example `Challenge.parse(header)` or `Challenge.NONE`).
- `handleUnauthorized(resource, wwwAuthenticate, status)`: handles a 401 or 403 and returns a
  `TokenResult` (`shouldRetry()`, `accessToken()`, `failure()`). It never throws for discovery,
  registration or grant failures; it gives up with a reason.
- `OAuthClient.canonicalResource(uri)`: the canonical form sent as `resource` and expected as
  audience (lowercase scheme and host, no fragment, no default port, no trailing slash).

Other failures are `AgentWeaveException`s: `AUTHENTICATION` for discovery, registration, grant and
policy refusals, `CONFIGURATION` for bad builder input, `CONNECTION` for I/O and `PROTOCOL` for
malformed JSON.

## Resource server

Protect any AgentWeave host by publishing protected resource metadata and validating tokens. This
is the composition the MCP end-to-end test uses:

```java
URI resource = URI.create("https://tools.example.com/mcp");
ProtectedResourceMetadata metadata = ProtectedResourceMetadata.builder(resource)
        .authorizationServer(URI.create("https://auth.example.com"))
        .scopesSupported("mcp:tools")
        .build();

BearerTokenAuthenticator auth = BearerTokenAuthenticator.builder(metadata,
                JwtTokenVerifier.builder()
                        .jwksUri(URI.create("https://auth.example.com/jwks"))
                        .issuer("https://auth.example.com")
                        .audience(resource)
                        .build())
        .requiredScopes("mcp:tools")
        .build();

try (AgentWeaveServer server = AgentWeaveServer.builder().port(8080)
        .endpoints(new ProtectedResourceMetadataEndpoint(metadata), McpToolServer.http(tools))
        .authenticator(auth)
        .start()) {
    server.join();
}
```

- **`ProtectedResourceMetadata`** needs at least one authorization server (MCP requires it); it
  also takes `scopesSupported`, `resourceDocumentation` and `resourceName`. The resource is stored
  in canonical form.
- **`ProtectedResourceMetadataEndpoint`** is a `ServerEndpointProvider` serving the metadata with
  `GET` at the path-inserted well-known URI (`/.well-known/oauth-protected-resource/mcp` here).
  `new ProtectedResourceMetadataEndpoint(metadata, true)` also serves it at the root well-known
  URI, for clients that only try the root; use it when the host protects a single resource.
- **`BearerTokenAuthenticator`** requires `Authorization: Bearer`, verifies the token, checks that
  its audience includes this resource (whatever the verifier), and enforces scopes:
  `requiredScopes(...)` for every request, `requireScopes(method, pathPrefix, scopes...)` for
  matching requests (`method` may be `null`; all matching rules apply). `GET`, `HEAD` and `OPTIONS`
  under `/.well-known/oauth-protected-resource` are always allowed anonymously, because the host
  puts the same authenticator in front of every endpoint.
- **`JwtTokenVerifier`** validates JWT access tokens: signature against a JWKS (`jwksUri`, fetched,
  cached and refreshed on unknown key ids, or a fixed `jwks(json)`), algorithms RS256 and ES256 by
  default (`algorithms(...)`), `typ` `JWT`, `at+jwt` or absent, `exp` required, `nbf`, a clock skew
  (`clockSkew`, default 60 s), `iss` among the configured issuers, and `aud` containing the
  resource. Scopes come from `scope` (space-delimited) or `scp`. The JWKS URL must be HTTPS
  (literal loopback addresses are allowed, for tests).
- **Opaque tokens:** implement `TokenVerifier` yourself, for example with token introspection
  (RFC 7662), and report the audiences in the returned `VerifiedToken`.

| Request | Response |
|---|---|
| No token | 401, `WWW-Authenticate: Bearer resource_metadata="<url>", scope="<s>"` |
| Invalid, expired or wrong-audience token | 401, `Bearer error="invalid_token", resource_metadata="<url>", scope="<s>"` |
| Valid token without the required scopes | 403, `Bearer error="insufficient_scope", scope="<s>", resource_metadata="<url>"` |
| Valid token | Allowed as a `CallerIdentity` named after `sub` (else `client_id` or `azp`, else `oauth-client`), with attributes `scopes` and `claims` |

The `scope` of a 401 lists the scopes required for that request, else the metadata's
`scopes_supported`, else it's omitted. The `scope` of a 403 lists the required scopes plus those
already granted, so a step-up doesn't lose permissions.

The token itself is never stored in the `CallerIdentity`, in reasons or in logs: tools, agent
handlers and the [discovery permission filter](../concepts/security.md#scopes-and-permissions) see
only the verified subject, scopes (`BearerTokenAuthenticator.SCOPES`, a sorted `List<String>`) and
claims (`BearerTokenAuthenticator.CLAIMS`).

!!! note "Spring Boot"
    The starter has no OAuth properties. A `BearerTokenAuthenticator` bean becomes the
    `InboundAuthenticator` of every AgentWeave endpoint, but the starter doesn't mount
    `ProtectedResourceMetadataEndpoint`; register its servlet on Boot's container yourself, or use
    Spring Security's OAuth2 resource server, whose `SCOPE_x` authorities the starter maps to the
    caller's `scopes` (see [Spring Boot and Spring Security](../concepts/security.md#spring-boot-and-spring-security)).

## Specification coverage

Traceability against the MCP authorization specification `2025-11-25` (the module's `SPEC.md` has
the full table, with the code location of each item):

| Requirement | Level | Status |
|---|---|---|
| Server publishes RFC 9728 metadata with at least one authorization server | MUST | `ProtectedResourceMetadata`, `ProtectedResourceMetadataEndpoint` |
| Metadata advertised by `WWW-Authenticate` `resource_metadata` and/or well-known URI | MUST (one of) | Both |
| Server includes `scope` in the 401 challenge | SHOULD | Yes |
| Client parses `WWW-Authenticate` and responds to 401 | MUST | Yes |
| Client tries header URL, then path-inserted, then root well-known | MUST | Yes |
| Metadata `resource` matches the accessed resource | MUST (RFC 9728) | Yes, else refused |
| RFC 8414 and OIDC discovery in MCP's order; issuer matches | MUST | Yes |
| PKCE S256; refuse when not advertised | MUST | Yes |
| `resource` parameter on authorization, token and refresh requests | MUST | Yes |
| Canonical resource URI | MUST | Yes |
| Bearer token in the header only, never in the query | MUST | Yes |
| Registration priority: pre-registered, metadata document, DCR, ask the user | SHOULD | Yes ("ask the user" is an `AUTHENTICATION` error for the application) |
| Scope selection: challenge, then `scopes_supported`, then none | SHOULD | Yes |
| Step-up on 403 `insufficient_scope`, bounded | SHOULD | Yes |
| Invalid or expired token → 401; insufficient scope → 403 with `scope` | MUST / SHOULD | Yes |
| Token audience validated (RFC 8707) | MUST | Yes |
| Token validation per OAuth 2.1 §5.2 | MUST | `JwtTokenVerifier` |
| No token passthrough | MUST | Tokens never reach handlers |
| HTTPS authorization server endpoints; loopback or HTTPS redirect URIs | MUST | Yes |
| `state` used and verified | SHOULD | Yes |
| Secure token storage | MUST | `TokenStore` SPI; the default is in memory only |
| Client credentials grant | extension | `Grant.CLIENT_CREDENTIALS` |

**Not implemented:** `private_key_jwt`, DPoP, mTLS-bound tokens, token introspection and revocation
clients, generating or hosting the Client ID Metadata Document, persisting dynamic registrations
across restarts, the `2025-03-26` fallback to default `/authorize` and `/token` endpoints when no
metadata exists, and out-of-the-box verification of opaque tokens.

**Verification.** The module's tests run offline against a test authorization server built on
Nimbus (metadata variants, JWKS, PKCE-checking token endpoint, refresh, DCR) and cover each
discovery path, the refusals, both grants, the browser flow with forged-state and issuer-mismatch
cases, refresh, step-up limits, every registration option, every token-endpoint auth method,
persistent-store reuse and every resource-server response. `McpOAuthEndToEndTest` runs the real MCP
client and server: the client discovers the authorization server from the 401, obtains a
client-credentials token with `resource` and scope, retries, and calls a protected tool that sees
the token's subject and scopes; without an authorizer the client fails with `AUTHENTICATION`, and an
unknown client is rejected without endless retries.
