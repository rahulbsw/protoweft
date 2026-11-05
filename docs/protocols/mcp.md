# MCP

`agentweave-mcp` speaks the [Model Context Protocol](https://modelcontextprotocol.io) in both roles,
over **stdio** and **Streamable HTTP**, on the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`
`2.1.0`):

- **Client:** `McpClientConnector` exposes a remote MCP server's tools through the core
  `ToolConnector` API.
- **Server:** `McpToolServer` serves any `ToolSource` (`LocalTools`, a `ToolRegistry`, another
  connector) to MCP clients.

MCP SDK types never appear in AgentWeave's public API.

## Client over stdio

The connector launches the server as a subprocess and speaks JSON-RPC over its stdin/stdout.

```java
ToolConnector files = McpClientConnector
        .stdio("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .env("LOG_LEVEL", "warn")
        .credentials((protocol, target) -> List.of(new Credential.NamedSecret("GITHUB_TOKEN", githubToken)))
        .requestTimeout(Duration.ofSeconds(30))
        .clientInfo("my-app", "1.4.0")
        .build();
files.start();
```

| Builder method | Default | Meaning |
|---|---|---|
| `stdio(command, args...)` | — | The server command line. |
| `env(name, value)` | none | An environment variable for the server process. |
| `credentials(provider)` | none | Looked up with the command as target. Only `NamedSecret`s apply; each becomes an environment variable. Any other credential fails `start()` with `AUTHENTICATION` before the process starts. |
| `requestTimeout(duration)` | 20 s | Timeout for initialization and for each request. |
| `clientInfo(name, version)` | `agentweave` `0.1.0` | The `clientInfo` sent in `initialize`. |
| `observations(handler)` | none | See [observability](../concepts/errors-and-observability.md#observability-hooks). |

`close()` ends the session and the subprocess.

## Client over Streamable HTTP

```java
ToolConnector remote = McpClientConnector.http("https://tools.example.com")
        .endpoint("/mcp")                                  // the default
        .header("X-Tenant", "acme")
        .credentials((protocol, target) -> List.of(new Credential.BearerToken(apiToken)))
        .build();
remote.start();
```

| Builder method | Default | Meaning |
|---|---|---|
| `http(baseUrl)` | — | An absolute `http` or `https` URL; anything else is rejected with `IllegalArgumentException`. |
| `endpoint(path)` | `/mcp` | The MCP endpoint, resolved against the base URL. |
| `header(name, value)` | none | A header sent with every request. A credential with the same name wins. |
| `credentials(provider)` | none | Looked up with the base URL as target. `BearerToken` → `Authorization: Bearer <token>`; `NamedSecret(name, value)` → header `name: value`. |
| `requestTimeout(duration)` | 20 s | Timeout for connecting, initialization and each request. |
| `clientInfo(name, version)` | `agentweave` `0.1.0` | The `clientInfo` sent in `initialize`. |
| `observations(handler)` | none | Observability hook. |

!!! note "Authorization flow"
    The MCP authorization flow (protected-resource metadata, token acquisition) isn't implemented.
    Supply a token through the `CredentialProvider`; a 401 or 403 from the server becomes
    `ErrorCategory.AUTHENTICATION` with the HTTP status as `protocolCode`.

## Using a client

Both transports behave the same once started:

- `start()` runs the `initialize` handshake. The negotiated revision is in
  `compliance().negotiated()`.
- `listTools()` returns the server's tools as `ToolSpec`s. MCP tool annotations and titles become
  [metadata](../concepts/tools.md#metadata); only hints the server set are copied.
- `invoke(invocation)` calls `tools/call`. `CallToolResult.isError` becomes `ToolOutcome.error()`,
  and `structuredContent` becomes the structured payload.
- The client declares no capabilities (no roots, sampling or elicitation handlers). If the server
  doesn't declare the `tools` capability, `listTools` and `invoke` fail with `UNSUPPORTED`.
- The connector is thread-safe once started.

## Serving tools over Streamable HTTP

`McpToolServer.http(tools)` is a `ServerEndpointProvider`: hand it to a host, which mounts one async
endpoint and runs the `InboundAuthenticator` before every request.

```java
McpToolServer.Http mcp = McpToolServer.http(tools)
        .serverInfo("weather", "1.0.0")
        .endpointPath("/mcp")
        .allowedOrigins("https://app.example.com", "http://localhost:*")
        .requestTimeout(Duration.ofSeconds(30));

try (AgentWeaveServer server = AgentWeaveServer.builder().port(8080).endpoints(mcp).start()) {
    server.join();
}
```

| Method | Default | Meaning |
|---|---|---|
| `serverInfo(name, version)` | `agentweave` `0.1.0` | Returned in `initialize`. |
| `endpointPath(path)` | `/mcp` | Exact path starting with `/`, no wildcards. The endpoint is named `mcp` for `/mcp` and `mcp:<path>` otherwise. |
| `allowedOrigins(origins...)` | none | Browser origins allowed to call the endpoint; `:*` matches any port. |
| `requestTimeout(duration)` | 60 s | The longest a `tools/call` may run before it fails with JSON-RPC `-32603`. The call is abandoned, but its thread isn't guaranteed to stop. |
| `observations(handler)` | none | Observes each `tools/call`, with the tool name as target. |
| `compliance()` | — | What the server implements. |
| `close()` | — | Closes all sessions and stops the SDK's session sweeper thread. Hosts call it when they destroy the servlet. |

- **The tool list is a snapshot.** The server reads `listTools()` once, on the first
  `endpoints()` call (when the host mounts it), and configuration is frozen from then on.
  `tools/list_changed` isn't offered. To serve a changed tool set, build a new server.
- **Caller propagation.** The caller established by the host's `InboundAuthenticator` is passed to
  `ToolSource.invoke(invocation, caller)`, so `@AgentTool` methods with a `CallerIdentity`
  parameter see who is calling.
- **Capabilities.** The server declares only `tools` (with `listChanged: false`).

### Origin validation

To protect against DNS rebinding, a request carrying an `Origin` header that isn't in
`allowedOrigins(...)` is answered with **403**, as MCP requires. Requests without an `Origin`
header (non-browser clients) pass. A browser-based client, such as the MCP Inspector UI, needs its
origin listed.

## Serving tools over stdio

```java
public static void main(String[] args) {
    LocalTools tools = LocalTools.builder().scan(new Greeter()).build();
    McpToolServer.stdio(tools).serverInfo("greeter", "1.0.0").run();   // returns when stdin closes
}
```

`run()` blocks until stdin reaches end of input, then closes the server. `Stdio` has the same
`serverInfo`, `requestTimeout`, `observations` and `compliance` methods as `Http`. Any MCP client
can launch it, including AgentWeave's: `McpClientConnector.stdio("java", "-cp", classpath,
"com.example.GreeterServer")`.

!!! warning "Keep stdout clean"
    stdout carries JSON-RPC. While `run()` is serving, `System.out` is redirected to `System.err` so
    stray prints can't corrupt the stream, but you must still configure your logging framework to
    write to stderr. The servlet API isn't needed at runtime for stdio serving.

Over stdio the caller is always `CallerIdentity.ANONYMOUS`: the process that launched the server is
the trust boundary.

## Content mapping

| MCP → AgentWeave (client) | |
|---|---|
| text | `Content.Text` |
| image, audio | `Content.Data` (base64 as received) |
| `resource_link` | `Content.Link` |
| embedded `resource` | `Content.Unmapped("resource")`, declared unsupported |
| `structuredContent` | `ToolOutcome.structured` |

| AgentWeave → MCP (server) | |
|---|---|
| `Content.Text` | text |
| `Content.Data` with an `image/*` or `audio/*` type | image or audio |
| `Content.Json` | text holding the serialized JSON |
| `Content.Link` | `resource_link` (the URI doubles as the name when the link has none) |
| `Content.Data` of any other type, `Content.Unmapped` | the call fails with `-32603`: MCP could carry them only as embedded resources, which needs an invented URI and the resources capability |
| `structured` | `structuredContent`, only when it is a `Map` (MCP requires a JSON object); no `outputSchema` is declared |

## Error mapping

**Client.** The JSON-RPC code (or HTTP status) is kept as `protocolCode`.

| MCP failure | `ErrorCategory` |
|---|---|
| `-32602` invalid params (unknown tool, malformed request, and invalid arguments on servers before `2025-11-25`) | `INVALID_ARGUMENTS` |
| `-32601` method not found | `UNSUPPORTED` |
| HTTP 401 or 403 | `AUTHENTICATION` |
| "Unsupported protocol version" during `initialize` | `VERSION_MISMATCH` |
| A timeout | `TIMEOUT` |
| Anything else during `initialize` | `CONNECTION` |
| Anything else afterwards | `PROTOCOL` |

**Server.** Follows the `2025-11-25` tools specification, which separates protocol errors from tool
execution errors:

| From the `ToolSource` | MCP response |
|---|---|
| A `ToolOutcome` with `error() == true` | `CallToolResult` with `isError: true` |
| `AgentWeaveException` with `TOOL_NOT_FOUND` | JSON-RPC `-32602`, like the SDK's own unknown-tool reply |
| `AgentWeaveException` with `INVALID_ARGUMENTS` | a tool execution error (`isError: true`, message as text), so a model can correct itself |
| `AgentWeaveException` with any other category | JSON-RPC `-32603` with the exception's message |
| Any other exception | JSON-RPC `-32603` `"internal error"` (its message may carry internals) |
| The call exceeds `requestTimeout` | JSON-RPC `-32603` |

## Coverage

Both roles report the same feature ids (`McpFeatures`); each report lists only the transport in
use as supported.

| Feature id | Client | Server |
|---|---|---|
| `lifecycle/version-negotiation` | ✓ | ✓ |
| `transport/stdio` | ✓ (stdio builder) | ✓ (`Stdio`) |
| `transport/streamable-http` | ✓ (HTTP builder) | ✓ (`Http`) |
| `tools/list`, `tools/call` | ✓ | ✓ |
| `tools/structured-output` | ✓ | ✓ |
| `content/text`, `content/image`, `content/audio`, `content/resource-link` | ✓ | ✓ |
| `transport/sse` | — | — |
| `authorization` | — | — |
| `tools/list-changed` | — | — |
| `content/resource` (embedded resources) | — | — |
| `resources`, `prompts`, `completion` | — | — |
| `logging`, `progress`, `cancellation` | — | — |
| `sampling`, `roots`, `elicitation` | — | — |

The client and HTTP server have been checked against independent implementations: the TypeScript
reference server `@modelcontextprotocol/server-everything` (stdio and Streamable HTTP), the SDK's own
client, and the MCP Inspector CLI.
