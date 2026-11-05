---
id: agentweave-mcp
type: module-design
status: draft
title: agentweave-mcp — MCP client connector and tool server (stdio, Streamable HTTP)
parent: agentweave-architecture
---

# agentweave-mcp

## Responsibility

Speaks MCP in both roles, over the **stdio** and **Streamable HTTP** transports, on the official
MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`, pinned to **2.1.0**, the latest GA release;
`2.2.0-M*` releases are milestones):

- **Client:** `McpClientConnector` exposes a remote MCP server's tools through the core
  `ToolConnector` API.
- **Server:** `McpToolServer` serves any core `ToolSource` (`LocalTools`, `ToolRegistry`, a
  connector) to MCP clients.

## Boundary

- Public API: `McpClientConnector` (with `Builder` for stdio and `HttpBuilder` for HTTP),
  `McpToolServer` (with `Http` and `Stdio`), and `McpFeatures` (feature ids). Every other type is
  package-private, and MCP SDK types never appear in a public signature.
- The client uses the SDK's blocking `McpSyncClient`. The server uses the SDK's async server
  internally so each tool call can carry a timeout. Reactor stays off the AgentWeave API.
- `McpToolServer.Http` is a `ServerEndpointProvider` (`agentweave-server`). It contributes one
  async `ServletEndpoint` wrapping the SDK's `HttpServletStreamableServerTransportProvider`. The
  endpoint is named `mcp` for the default path `/mcp`, and `mcp:<path>` for any other path. The
  servlet API is `compileOnly` because the host provides it. Stdio serving doesn't need the
  servlet API at runtime, and a test checks this.

## Decisions

- **Versions come from their sources, not literals.** Wire revisions in every
  `ComplianceReport` are read from the SDK transport in use (`protocolVersions()`):
  - stdio, client and server: `2024-11-05` through `2025-11-25`.
  - Streamable HTTP, client and server: `2025-03-26` through `2025-11-25`. Streamable HTTP
    doesn't exist in `2024-11-05`.

  The SDK version comes from a resource generated from `gradle.properties`. The client's
  negotiated revision is the one in the server's `initialize` result. A server negotiates per
  session, so its report never carries a negotiated revision.
- **Honest per-transport reports.** A report lists only the transport actually in use as
  supported. The other transport and `transport/sse` are listed as unsupported. Client and server
  use the same feature ids, because an id names a protocol feature and the report's owner
  provides the role.
- **No over-advertising on the wire.**
  - The client registers no roots, sampling, or elicitation handlers, so its capabilities are
    empty.
  - The server declares only `tools` (`listChanged: false`).
  - A server without the `tools` capability makes the client's `listTools`/`invoke` fail with
    `UNSUPPORTED`.
- **Client error mapping** (to `ErrorCategory`; the JSON-RPC code, or the HTTP status for
  auth failures, is kept as `protocolCode`):
  - `-32602` → `INVALID_ARGUMENTS`. It is used for unknown tools, malformed requests, and (by
    servers before `2025-11-25`) invalid arguments. The client can't tell these apart, so it
    doesn't guess `TOOL_NOT_FOUND`.
  - `-32601` → `UNSUPPORTED`.
  - HTTP 401/403 (the SDK's `McpHttpClientTransportAuthorizationException`) → `AUTHENTICATION`.
  - The SDK's "Unsupported protocol version" during `initialize` → `VERSION_MISMATCH`.
  - A `TimeoutException` anywhere in the cause chain → `TIMEOUT`.
  - Other failures during `initialize` → `CONNECTION`; afterwards → `PROTOCOL`.
- **Server error mapping** follows the `2025-11-25` tools spec, which splits protocol errors
  from tool execution errors:
  - `AgentWeaveException` with `TOOL_NOT_FOUND` → JSON-RPC `-32602`, the same as the SDK's own
    unknown-tool reply.
  - `INVALID_ARGUMENTS` → a tool execution error (`isError: true`, with the message as text).
    `2025-11-25` (SEP-1303) classifies input validation as a tool execution error, so the model
    can correct itself. The SDK's own schema validation answers the same way.
  - Any other category → `-32603` with the exception's message.
  - Non-AgentWeave exceptions → `-32603 "internal error"`. Their messages may carry internals.
  - A tool call that exceeds the server's `requestTimeout` (default 60 s) → `-32603`. The call
    is abandoned, but its thread isn't guaranteed to stop.
- **Tool failures are outcomes in both directions.** `ToolOutcome.error()` ⇄
  `CallToolResult.isError`.
- **Content mapping, client** (MCP → core): text → `Text`; image/audio → `Data` (base64 as
  received); `resource_link` → `Link`; embedded `resource` → `Unmapped`. It is declared
  unsupported, not dropped.
- **Content mapping, server** (core → MCP):
  - `Text` → text.
  - `Data` → image or audio content for `image/*` or `audio/*` mime types.
  - `Json` → text holding the serialized JSON.
  - `Link` → `resource_link`. MCP requires a name, so the URI is used when the link has none.
  - `Data` of any other mime type, and `Unmapped`, fail the call with `-32603`. MCP can carry
    them only as embedded resources, which would need an invented URI and the resources
    capability. Rejecting keeps the loss visible.
  - `structured` becomes `structuredContent` only when it is a `Map`, because MCP requires a
    JSON object. No `outputSchema` is declared.
- **Caller propagation.** Over HTTP, the host's `InboundAuthFilter` authenticates every request.
  The SDK's `contextExtractor` copies `InboundAuthFilter.caller(request)` into the per-request
  transport context, and the call handler passes it to `ToolSource.invoke(invocation, caller)`.
  Over stdio, the caller is `CallerIdentity.ANONYMOUS`, because the process launcher is the
  trust boundary.
- **Credentials.**
  - Over stdio: MCP's authorization spec covers HTTP, and stdio servers read credentials from
    their environment. So `NamedSecret` becomes an environment variable of the server process.
    Any other credential kind fails with `AUTHENTICATION` before the process starts. The
    credential target is the command.
  - Over HTTP: `BearerToken` → `Authorization: Bearer <token>`, and `NamedSecret(name, value)`
    → header `name: value`. Credentials override static `header(...)` values with the same
    name. The credential target is the base URL as configured. The MCP authorization flow
    (protected-resource metadata, token acquisition) is not implemented.
- **Telemetry targets carry no secrets.** The observation target is the executable (never its
  arguments) for stdio. For HTTP it is the base URL without user info, query, or fragment. The
  server observes each `tools/call`, with the tool name as target.
- **Browser origins (DNS rebinding).** The HTTP server installs the SDK's
  `DefaultServerTransportSecurityValidator`. A request carrying an `Origin` that isn't in
  `allowedOrigins(...)` gets 403, as MCP requires. Requests with no `Origin` (non-browser
  clients) pass.
- **Tool list is a snapshot.** The server reads `listTools()` once, when it is built: on the
  first `endpoints()` call for HTTP, or in `run()` for stdio. Configuration is frozen from then
  on. `tools/list_changed` isn't offered.
- **stdio serving.** `Stdio.run()` blocks until stdin reaches EOF, then closes the server.
  While it runs, `System.out` is redirected to `System.err`, so stray prints can't corrupt the
  JSON-RPC stream. Applications must still configure logging to stderr.

## Coverage

Supported and unsupported feature ids are listed in `McpFeatures`, and tests assert them for
the client on both transports and for the server on both transports. Implemented:
- version negotiation
- the transport in use
- `tools/list` and `tools/call`
- structured output
- text, image, audio, and resource-link content

Not implemented in either role:
- the SSE transport
- MCP authorization
- `tools/list_changed`
- embedded resources
- resources, prompts, and completion
- logging, progress, and cancellation
- sampling, roots, and elicitation

## Verification

- **stdio client:** tests launch a real SDK-built stdio server as a subprocess.
- **HTTP client ↔ HTTP server:** tests host `McpToolServer.http` on embedded Jetty (port 0) with
  an `InboundAuthenticator`. They cover:
  - negotiation and the compliance report
  - structured output, tool errors, unknown tools, and invalid arguments
  - JSON-RPC internal errors and the tool timeout
  - content mapping
  - bearer and named-secret credentials, and 401 → `AUTHENTICATION`
  - origin rejection
  - the caller identity seen by the tool
- **SDK client against our HTTP server:** the SDK's own `McpSyncClient` with
  `HttpClientStreamableHttpTransport`, independent of our client.
- **stdio server:** a subprocess running `McpToolServer.stdio`, with the servlet API removed
  from its classpath. It is driven by our stdio client, and tests check that it exits when stdin
  closes and that stray stdout prints don't corrupt the protocol.
- **Manual interop:**
  - Our HTTP client against the TypeScript reference server
    (`@modelcontextprotocol/server-everything streamableHttp`): it negotiated `2025-11-25`,
    listed its tools, called `echo`, and mapped image and resource-link content. This can be
    rerun with `ManualInteropTest` (`AGENTWEAVE_MCP_INTEROP_URL`).
  - The MCP Inspector CLI (2.13.0) against our HTTP server, with a bearer token: it listed the
    tools, returned `structuredContent` from `add`, and showed the caller identity from `whoami`.
  - The stdio client against server-everything (stdio) negotiated `2025-11-25`.
