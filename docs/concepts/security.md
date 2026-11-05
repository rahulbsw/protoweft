# Security and credentials

AgentWeave separates **outbound** credentials, which it presents to servers it calls, from
**inbound** authentication, which decides who may call the endpoints you expose. It includes no
identity provider. Both sides are hooks you implement or connect to your existing security
stack.

## Outbound: `CredentialProvider`

Connectors ask a `CredentialProvider` for credentials when they connect:

```java
CredentialProvider creds = (protocol, target) ->
        List.of(new Credential.BearerToken(tokenCache.tokenFor(target)));
```

| Credential | Over HTTP (MCP Streamable HTTP, A2A, UTCP discovery) | Over MCP stdio |
|---|---|---|
| `BearerToken` | `Authorization: Bearer <token>` | Rejected with `AUTHENTICATION` |
| `NamedSecret(name, value)` | Header `name: value` | Set as environment variable `name` for the server process |

MCP stdio servers read credentials from their environment, not from the HTTP authorization flow,
which is why bearer tokens are rejected there. A UTCP manual also declares how each of its tools
authenticates (`api_key`, `basic`, `oauth2`). Those values come from UTCP variables; see the UTCP
guide.

Credentials never reach observation hooks. Telemetry sees only the target (an executable or a
base URL).

## Inbound: `InboundAuthenticator`

Every AgentWeave server endpoint calls one `InboundAuthenticator` before handling a request. That
covers MCP over HTTP, the A2A agent card and JSON-RPC endpoint, and the UTCP manual and tool
routes, so a single implementation protects all three protocols:

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

- A denied request gets HTTP 401 (with `WWW-Authenticate: Bearer`) or 403, and a JSON body
  `{"error": "<reason>"}`.
- An allowed request carries the `CallerIdentity` through to your code. Local tools receive it as
  a `CallerIdentity` parameter, and agent handlers receive it as `AgentRequest.caller()`.
- If the authenticator throws, the request is rejected with 401.
- `InboundAuthenticator.ALLOW_ALL` admits everyone as `CallerIdentity.ANONYMOUS`. It's the default
  for the embedded Jetty host, so set an authenticator before exposing a server beyond localhost.

In Spring Boot, protect the endpoint paths with Spring Security as usual. Define an
`InboundAuthenticator` bean only if you also need the AgentWeave hook.
