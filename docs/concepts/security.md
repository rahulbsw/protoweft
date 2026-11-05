# Security and credentials

AgentWeave separates **outbound** credentials, which it presents to servers it calls, from
**inbound** authentication, which decides who may call the endpoints you expose. It ships no
identity provider: both sides are hooks you implement or connect to your existing security stack.
The optional [`agentweave-oauth`](../integrations/oauth.md) module implements both hooks for OAuth
2.1, as MCP authorization specifies it.

## Outbound: `CredentialProvider`

Connectors ask a `CredentialProvider` for credentials. It receives the protocol and the target the
connector is talking to, and returns a list of `Credential`s (empty for none):

```java
CredentialProvider creds = (protocol, target) ->
        List.of(new Credential.BearerToken(tokenCache.tokenFor(target)));

CredentialProvider byTarget = (protocol, target) -> target.startsWith("https://tools.example.com")
        ? List.of(new Credential.NamedSecret("X-Api-Key", apiKey))
        : List.of();
```

Each connector presents credentials the way its protocol and transport expect:

| Connector | Target passed to the provider | `BearerToken` | `NamedSecret(name, value)` | When |
|---|---|---|---|---|
| MCP, stdio | the command | rejected with `AUTHENTICATION` | environment variable `name` of the server process | once, before the process starts |
| MCP, Streamable HTTP | the base URL as configured | `Authorization: Bearer <token>` | header `name: value` | when the connector starts |
| A2A | the agent base URL | `Authorization: Bearer <token>` | header `name: value` | on the card fetch and on every call |
| UTCP, http manual | the manual URL without query or fragment | `Authorization: Bearer <token>` | header `name: value` | on the manual discovery request only |

- MCP stdio servers read credentials from their environment. MCP's authorization flow covers HTTP
  only, which is why a bearer token can't be used over stdio.
- On MCP HTTP, credentials override static `header(...)` values with the same name. To obtain
  tokens on demand instead of supplying them, give the connector an
  [`HttpAuthorizer`](#on-demand-tokens-httpauthorizer).
- On UTCP, credentials override the manual's static headers and are themselves overridden by the
  manual call template's own UTCP `auth`. **Tool calls never see them**: each UTCP tool
  authenticates through the `auth` object in its own call template (`api_key`, `basic` or
  `oauth2`), whose values usually come from UTCP variables. See
  [UTCP authentication](../protocols/utcp.md#authentication).
- A2A agent cards may declare `securitySchemes` (OAuth, OIDC, mTLS). AgentWeave doesn't interpret
  them; it sends the static credentials your provider returns.

### On-demand tokens: `HttpAuthorizer`

A `CredentialProvider` hands out credentials you already have. An `HttpAuthorizer`, also in the
core (`io.agentweave.core.auth`), obtains them when a server asks for them:

```java
public interface HttpAuthorizer {
    Optional<String> authorization(URI resource);                              // before every request
    boolean onChallenge(URI resource, int status, String wwwAuthenticate);     // on a 401 or 403: retry?
}
```

The MCP Streamable HTTP connector accepts one with `McpClientConnector.http(..).authorizer(..)`;
its `Authorization` header replaces a static header of the same name. `agentweave-oauth`'s
`OAuthClient` implements it with the full MCP authorization flow: protected resource metadata,
authorization server discovery, client registration, PKCE, refresh and step-up. See
[MCP authorization](../protocols/mcp.md#authorization) and [OAuth](../integrations/oauth.md). The
A2A and UTCP connectors don't take an authorizer; they use the `CredentialProvider` only.

Credentials never reach observation hooks. Telemetry sees only the target: the executable for MCP
stdio (never its arguments), and a URL without user info, query or fragment for HTTP.

## Inbound: `InboundAuthenticator`

Every AgentWeave server endpoint runs one `InboundAuthenticator` before handling a request. That
covers MCP over HTTP, **both** A2A endpoints (the agent card and JSON-RPC), and the UTCP manual and
tool routes, so one implementation protects all three protocols.

```java
InboundAuthenticator apiKeys = request -> request.header("X-Api-Key")
        .flatMap(keyStore::lookup)                       // your key store
        .map(owner -> AuthDecision.allow(CallerIdentity.of(owner)))
        .orElse(AuthDecision.unauthenticated("missing or invalid API key"));

InboundAuthenticator bearer = request -> request.bearerToken()
        .flatMap(jwtVerifier::verify)                    // your JWT library
        .map(claims -> AuthDecision.allow(new CallerIdentity(claims.subject(), Map.of("scopes", claims.scopes()))))
        .orElse(AuthDecision.unauthenticated("bearer token required"));
```

The `InboundRequest` gives you the protocol, HTTP method, path (without the context path), headers
(case-insensitive) and remote address, plus `header(name)` and `bearerToken()` helpers.

| Decision | Response |
|---|---|
| `AuthDecision.allow(caller)` | The request proceeds; `caller` reaches your code. |
| `AuthDecision.unauthenticated(reason)` | HTTP 401 with `WWW-Authenticate: Bearer` and `{"error": "<reason>"}`. |
| `AuthDecision.unauthenticated(reason, challenge)` | HTTP 401 with `WWW-Authenticate: <challenge>`, for example an OAuth `resource_metadata` challenge. |
| `AuthDecision.forbidden(reason)` | HTTP 403 with `{"error": "<reason>"}`. |
| `AuthDecision.forbidden(reason, challenge)` | HTTP 403 with `WWW-Authenticate: <challenge>`, for example `Bearer error="insufficient_scope", ...`. |
| The authenticator throws | HTTP 401, `{"error": "authentication failed"}`. |

An allowed caller reaches local tools as a `CallerIdentity` parameter, `ToolSource.invoke(invocation,
caller)` implementations as the `caller` argument, and agent handlers as `AgentRequest.caller()`.
The A2A server also uses the caller's name for [task ownership](../protocols/a2a.md#task-ownership).

!!! warning "The A2A agent card is behind the authenticator too"
    The authenticator also guards `GET /.well-known/agent-card.json`. If your card should be public,
    as A2A discovery usually expects, admit that route anonymously:

    ```java
    InboundAuthenticator withPublicCard = request ->
            request.method().equals("GET") && request.path().equals(A2aAgentServer.AGENT_CARD_PATH)
                    ? AuthDecision.allow(CallerIdentity.ANONYMOUS)
                    : bearer.authenticate(request);
    ```

### OAuth resource server

`agentweave-oauth`'s `BearerTokenAuthenticator` is a ready-made `InboundAuthenticator` for OAuth 2.1
bearer tokens, as the MCP authorization specification requires them: it validates the token
(`JwtTokenVerifier` by default), checks that its audience is this server, enforces required scopes,
and answers with the exact `WWW-Authenticate` challenges clients use to discover the authorization
server. Pair it with a `ProtectedResourceMetadataEndpoint` on the same host. See
[OAuth: resource server](../integrations/oauth.md#resource-server).

### Defaults

- `InboundAuthenticator.ALLOW_ALL` admits everyone as `CallerIdentity.ANONYMOUS`. It is the
  default of the embedded Jetty host, which binds `localhost` unless you choose another host. Set
  an authenticator before exposing a server beyond your machine.
- Over MCP stdio there's no HTTP request: the caller is always `CallerIdentity.ANONYMOUS`, and the
  process that launched the server is the trust boundary.
- UTCP servers can announce how clients should authenticate, so UTCP clients send the right header
  on tool calls. See [announcing authentication](../protocols/utcp.md#announcing-authentication).

## Scopes and permissions

By convention, the caller's granted OAuth scopes are the `scopes` attribute of `CallerIdentity`, a
collection of strings. AgentWeave's authenticators fill it:

| Authenticator | `scopes` attribute |
|---|---|
| `BearerTokenAuthenticator` (`agentweave-oauth`) | The token's `scope` or `scp` claim, as a sorted list (`BearerTokenAuthenticator.SCOPES`); the verified claims are under `claims`. |
| `SpringSecurityInboundAuthenticator` (Spring Boot starter) | Spring Security's `SCOPE_x` authorities with the prefix removed. |
| Your own | Whatever you put there, for example `Map.of("scopes", claims.scopes())` as above. |

Where scopes take effect:

- **Tool discovery.** A [`CapabilityCatalog`](../discovery/index.md#source-policies) gives sources
  and tools required scopes (`SourcePolicy.requiredScopes`, `toolScopes`; in Spring Boot,
  `agentweave.discovery.sources.<name>.required-scopes`). A search only returns capabilities whose
  required scopes the caller holds (`DiscoveryQuery.SCOPES_ATTRIBUTE`), never names the hidden
  ones, and `call_capability` or `CapabilityCatalog.invoke` re-checks before calling. The
  [Spring AI](../integrations/spring-ai.md#tool-search-with-the-catalog) and
  [ARGI](../integrations/argi.md#catalog-tool-selection) adapters pass the request's caller to the
  catalog, so a model only sees the tools that caller may use.
- **Per-request rules.** `BearerTokenAuthenticator.requiredScopes(...)` and
  `requireScopes(method, pathPrefix, ...)` reject requests whose token lacks the scopes, with a
  403 `insufficient_scope` challenge a client can step up from.
- **Your own code.** Tools, agent handlers and resource handlers see the `CallerIdentity` and can
  check `caller.attributes().get("scopes")` themselves.

`BearerTokenAuthenticator` never puts the token itself into the caller identity, so it can't leak
to tools or be passed through to other servers. Keep it that way in your own authenticators.

## Spring Boot and Spring Security

The Spring Boot starter installs the same `InboundAuthFilter` in front of every endpoint, ordered
after Spring Security's filter chain, so both can apply:

1. **Spring Security** protects the protocol paths like any other route (HTTP Basic, OAuth2
   resource server, and so on). Its CSRF protection must ignore the protocol paths, because
   protocol clients don't send CSRF tokens.
2. **The AgentWeave authenticator** then runs. If you define no `InboundAuthenticator` bean and
   Spring Security is on the classpath, the starter uses `SpringSecurityInboundAuthenticator`: it
   never rejects, and it names the caller after Spring Security's `Authentication`, with the
   sorted authority names as the `authorities` attribute and the `SCOPE_x` authorities, prefix
   removed, as the `scopes` attribute (anonymous requests stay `CallerIdentity.ANONYMOUS`). With
   Spring Security's OAuth2 resource server, those are the token's scopes. Without Spring
   Security, the default is `ALLOW_ALL`.
3. If you **do** define an `InboundAuthenticator` bean, it replaces that default. It runs after
   Spring Security; read Spring's `SecurityContextHolder` yourself if you still want its user as
   the caller.

See [Spring Boot: security](../integrations/spring-boot.md#security) for a configuration example.
