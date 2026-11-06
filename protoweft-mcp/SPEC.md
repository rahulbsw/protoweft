---
id: protoweft-mcp
type: module-design
status: draft
title: protoweft-mcp — MCP client connector and tool server (stdio, Streamable HTTP)
parent: protoweft-architecture
---

# protoweft-mcp

## Responsibility

Speaks MCP in both roles, over the **stdio** and **Streamable HTTP** transports, on the official
MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`, pinned to **2.1.0**, the latest GA release;
`2.2.0-M*` releases are milestones):

- **Client:** `McpClientConnector` exposes a remote MCP server's tools through the core
  `ToolConnector` API, and its resources, prompts, completion, logging, progress and
  list-change notifications through MCP-specific methods.
- **Server:** `McpToolServer` serves any core `ToolSource` (`LocalTools`, `ToolRegistry`, a
  connector) to MCP clients, plus optional `McpResources` and `McpPrompts`, with logging,
  progress and completion.

## Boundary

- Public API: `McpClientConnector` (with `Builder` for stdio and `HttpBuilder` for HTTP),
  `McpToolServer` (with `Http` and `Stdio`), `McpFeatures` (feature ids), and the resource and
  prompt types:
  - authoring: `McpResources`, `McpPrompts`, the handler interfaces `McpResourceHandler`,
    `McpPromptHandler`, `McpCompleter` and their request records, and the annotations
    `@McpResource`, `@McpResourceTemplate`, `@McpPrompt`, `@McpComplete`;
  - neutral records shared by both roles: `McpResourceInfo`, `McpTemplateInfo`,
    `McpResourceContents`, `McpPromptInfo`, `McpPromptArgument`, `McpPromptMessage`,
    `McpPromptResult`, `McpCompletion`, `McpLogMessage`.

  Every other type is package-private, and MCP SDK types never appear in a public signature.
  Resources and prompts are MCP-only concepts, so they live here and not in `protoweft-core`.
- The client uses the SDK's blocking `McpSyncClient`. The server uses the SDK's async server
  internally so each tool call can carry a timeout. Reactor stays off the Protoweft API.
- `McpToolServer.Http` is a `ServerEndpointProvider` (`protoweft-server`). It contributes one
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
  - The server declares `tools` (`listChanged: false`) and `logging` (SDK 2.1.0 adds `logging`
    to every server anyway). It adds `resources` (`subscribe: true, listChanged: false`) only
    when given `McpResources`, `prompts` (`listChanged: false`) only when given `McpPrompts`,
    and `completions` only when at least one completer is registered.
  - The client checks the server's declared capability before each call and fails with
    `UNSUPPORTED` when it is missing: `tools` for `listTools`/`invoke`, `resources` for
    listing and reading, `resources.subscribe` for `subscribe`/`unsubscribe`, `prompts`,
    `completions`, and `logging` for `setLogLevel`.
- **Client error mapping** (to `ErrorCategory`; the JSON-RPC code, or the HTTP status for
  auth failures, is kept as `protocolCode`):
  - `-32602` → `INVALID_ARGUMENTS`. It is used for unknown tools, malformed requests, and (by
    servers before `2025-11-25`) invalid arguments. The client can't tell these apart, so it
    doesn't guess `TOOL_NOT_FOUND`.
  - `-32002` (resource not found) → `INVALID_ARGUMENTS`: the URI is the caller's argument, and
    core has no not-found category besides `TOOL_NOT_FOUND`.
  - `-32601` → `UNSUPPORTED`.
  - HTTP 401/403 (the SDK's `McpHttpClientTransportAuthorizationException`) → `AUTHENTICATION`.
  - The SDK's "Unsupported protocol version" during `initialize` → `VERSION_MISMATCH`.
  - A `TimeoutException` anywhere in the cause chain → `TIMEOUT`.
  - Other failures during `initialize` → `CONNECTION`; afterwards → `PROTOCOL`.
- **Server error mapping** follows the `2025-11-25` tools spec, which splits protocol errors
  from tool execution errors:
  - `ProtoweftException` with `TOOL_NOT_FOUND` → JSON-RPC `-32602`, the same as the SDK's own
    unknown-tool reply.
  - `INVALID_ARGUMENTS` → a tool execution error (`isError: true`, with the message as text).
    `2025-11-25` (SEP-1303) classifies input validation as a tool execution error, so the model
    can correct itself. The SDK's own schema validation answers the same way.
  - Any other category → `-32603` with the exception's message.
  - Non-Protoweft exceptions → `-32603 "internal error"`. Their messages may carry internals.
  - A tool call that exceeds the server's `requestTimeout` (default 60 s) → `-32603`. The call
    is abandoned, but its thread isn't guaranteed to stop.
- **Tool failures are outcomes in both directions.** `ToolOutcome.error()` ⇄
  `CallToolResult.isError`.
- **Content mapping, client** (MCP → core): text → `Text`; image/audio → `Data` (base64 as
  received); `resource_link` → `Link`; embedded `resource` → `Resource` (URI, mime type, and
  text or base64 blob). The same mapping applies to tool results and prompt messages.
- **Content mapping, server** (core → MCP):
  - `Text` → text.
  - `Data` → image or audio content for `image/*` or `audio/*` mime types.
  - `Json` → text holding the serialized JSON.
  - `Link` → `resource_link`. MCP requires a name, so the URI is used when the link has none.
  - `Resource` → embedded `resource` (text or blob contents). Found by the official conformance
    suite: before `Resource` existed, a `Link` + `Text` pair went out as `resource_link` + text,
    so the advertised embedded-resource support did not hold for tool results.
  - `Data` of any other mime type, and `Unmapped`, fail the call with `-32603`. MCP can carry
    them only as embedded resources, which would need an invented URI and the resources
    capability. Rejecting keeps the loss visible.
  - `structured` becomes `structuredContent` only when it is a `Map`, because MCP requires a
    JSON object. No `outputSchema` is declared.
- **Caller propagation.** Over HTTP, the host's `InboundAuthFilter` authenticates every request.
  The SDK's `contextExtractor` copies `InboundAuthFilter.caller(request)` into the per-request
  transport context, and the call handler passes it to `ToolSource.invoke(invocation, caller, progress)`;
  resource, prompt and completion handlers read it the same way.
  Over stdio, the caller is `CallerIdentity.ANONYMOUS`, because the process launcher is the
  trust boundary.
- **Credentials.**
  - Over stdio: MCP's authorization spec covers HTTP, and stdio servers read credentials from
    their environment. So `NamedSecret` becomes an environment variable of the server process.
    Any other credential kind fails with `AUTHENTICATION` before the process starts. The
    credential target is the command.
  - Over HTTP: `BearerToken` → `Authorization: Bearer <token>`, and `NamedSecret(name, value)`
    → header `name: value`. Credentials override static `header(...)` values with the same
    name. The credential target is the base URL as configured. The MCP authorization flow is
    an `HttpAuthorizer` instead (see Authorization below).
- **Telemetry targets carry no secrets.** The observation target is the executable (never its
  arguments) for stdio. For HTTP it is the base URL without user info, query, or fragment. The
  server observes each `tools/call`, with the tool name as target.
- **Browser origins (DNS rebinding).** The HTTP server installs the SDK's
  `DefaultServerTransportSecurityValidator`. A request carrying an `Origin` that isn't in
  `allowedOrigins(...)` gets 403, as MCP requires. Requests with no `Origin` (non-browser
  clients) pass.
- **Prompt messages** (`McpPromptMessage(role, List<Content>)`). MCP gives a prompt message one
  content part. On the server, that part maps like tool content (`McpPromptMessage.resource(...)`
  builds an embedded-resource message); more than one part fails the call with `-32603`.
- **Resource and prompt authoring.**
  - Builders (`McpResources.builder()`, `McpPrompts.builder()`) and annotation scanning
    (`McpResources.scan(obj)`, `McpPrompts.scan(obj)`, or `.scan(...)` on a builder) produce the
    same entries, as `@AgentTool` does for tools. Annotated parameters bind by name
    (`@ToolParam` sets name, description and `required`; `Optional` is never required) and
    convert from strings to `String`/`int`/`long`/`double`/`boolean`, since MCP prompt arguments
    and URI variables are strings. `CallerIdentity` and the request record are injected.
  - Resource handlers return `String` (text, default `text/plain`), `byte[]` (blob, default
    `application/octet-stream`), `McpResourceContents` (or a list), or any other value as JSON
    text (default `application/json`). Prompt handlers return a `String` (one user message),
    messages, or an `McpPromptResult`.
  - URI templates are RFC 6570 simple `{var}` expressions, matched by the SDK's template
    manager; extracted values are percent-decoded before the handler sees them.
  - Handlers run on Reactor's bounded-elastic pool with the server's `requestTimeout`, observed
    as `resources/read`, `prompts/get` and `completion/complete`. Errors map like tool protocol
    errors: `INVALID_ARGUMENTS` (including a missing required prompt argument, checked before
    the handler) → `-32602`, other `ProtoweftException`s → `-32603` with their message,
    anything else → `-32603 "internal error"`. Unknown URIs get the SDK's `-32002`, unknown
    prompts its `-32602`.
  - Handlers and completers receive the caller exactly as tools do (see "Caller propagation").
- **Subscriptions.** The SDK tracks `resources/subscribe` per session.
  `McpResources.notifyUpdated(uri)` sends `notifications/resources/updated` to subscribed
  sessions of every server the `McpResources` is bound to; a server unbinds when it closes.
  Delivery failures are dropped so a vanished subscriber can't fail the publisher. On the
  client, the SDK re-reads the resource on each update and `subscribe(uri, listener)` receives
  the contents, routed by their URI.
- **Completion.** One completer per prompt argument or template variable. The server returns
  at most 100 values (MCP's cap) with `total` set to the full count and `hasMore` when
  truncated; an argument without a completer gets an empty result. `McpCompleter.of(list)`
  filters by case-insensitive prefix.
- **Logging and progress, server.** Each `tools/call` gets a session-bound `ToolProgress`
  passed to `ToolSource.invoke(invocation, caller, progress)`.
  - `log(level, msg)` → `notifications/message` with the tool name as `logger`. `Level` maps
    one-to-one onto MCP's RFC 5424 levels; the SDK filters by the session's `logging/setLevel`
    (default `info`).
  - `progress(done, total, msg)` → `notifications/progress`, only when the request carried
    `_meta.progressToken`, echoing that token.
  - Each notification is sent (bounded wait) before `progress`/`log` returns, so it precedes
    the result on the wire; failures are swallowed because `ToolProgress` never throws.
- **Logging and progress, client.** `invoke(invocation, caller, progress)` sends a fresh
  `progressToken` only when `progress` isn't `NOOP`, and routes matching
  `notifications/progress` to it. `onLog` receives every log message; `setLogLevel` sends
  `logging/setLevel`. MCP doesn't tie log messages to a request, so they go to connector-level
  listeners, not to a call's `ToolProgress`. The SDK's sync client runs each notification
  consumer on its own worker thread, which reorders them; the connector therefore taps
  progress and log notifications on the transport (`NotificationTap`), in arrival order and
  before the response that follows them.
- **List changes.** The client's `onToolsChanged`/`onResourcesChanged`/`onPromptsChanged`
  receive the re-listed items the SDK fetches on each `list_changed` notification.
- **Tool list is a snapshot.** The server reads `listTools()` once, when it is built: on the
  first `endpoints()` call for HTTP, or in `run()` for stdio. Configuration is frozen from then
  on. `McpResources` and `McpPrompts` are immutable once built, for the same reason. The
  server offers no `list_changed` notifications: the SDK can add entries at runtime but keeps
  completers in a fixed map, so runtime additions couldn't complete.
- **stdio serving.** `Stdio.run()` blocks until stdin reaches EOF, then closes the server.
  While it runs, `System.out` is redirected to `System.err`, so stray prints can't corrupt the
  JSON-RPC stream. Applications must still configure logging to stderr.
- **Tool metadata.** `ToolSpec` metadata hints (read-only, destructive, idempotent,
  open-world, title) map to and from MCP tool annotations in both directions. Only the hints a
  server actually sets are carried over.

## Authorization (MCP 2025-11-25)

- **Client.** `McpClientConnector.http(..).authorizer(HttpAuthorizer)` wires the SDK's request
  customizer and its authorization-error handler.
  - The authorizer sets `Authorization` on every request, replacing any static header.
  - On a 401 or 403 it decides whether to retry. The SDK bounds retries.
  - The OAuth resource is the MCP endpoint URL.
  - `HttpAuthorizer` is a core interface, so this module doesn't depend on `protoweft-oauth`.
    `OAuthClient` implements it: discovery, PKCE, registration, refresh and step-up.
  - The client's report lists `authorization` as supported only when an authorizer is
    configured.
- **Server.** Compose `protoweft-oauth`'s `ProtectedResourceMetadataEndpoint` next to
  `McpToolServer.http(..)`, and use its `BearerTokenAuthenticator` as the host's
  `InboundAuthenticator`.
  - The challenge headers come from `InboundAuthFilter`, because the SDK's
    `ServerHttpHeaderValidator` can only `sendError`.
  - The token's subject and scopes reach tools as the `CallerIdentity`.
  - `McpToolServer`'s own report still lists `authorization` as unsupported, because the server
    module can't see what the host composed. That limits honesty in one direction only: we never
    over-claim.
- **Verification.** `protoweft-oauth`'s `McpOAuthEndToEndTest` runs a real MCP client and server.
  The client discovers the authorization server from the 401, obtains a client-credentials token
  with `resource` and scope, retries, and calls a protected tool, which sees the token subject and
  scopes. Without an authorizer the client fails with `AUTHENTICATION`, and an unknown client is
  rejected without endless retries.

## SDK workarounds

- **Session sweeper thread leak (SDK 2.1.0; still present on `main` and in `v2.2.0-M2`).**
  `HttpServletStreamableServerTransportProvider` runs its idle-session sweeper on
  `Schedulers.newSingle(...)`, Reactor's single-thread scheduler. Its thread is non-daemon, and
  `closeGracefully()` only disposes the subscription, never the scheduler, so a host that stops
  the server never exits.
  - **Workaround:** while building the provider, `SweeperCapture` registers Reactor's official
    `Schedulers.addExecutorServiceDecorator` hook, keyed and removed immediately after. It records
    the sweeper's executor, and `McpToolServer.Http.close()` shuts that executor down after
    `closeGracefully()`.
  - **Triggers:** `ClosingServlet` calls `close()` when the host destroys the servlet, so Jetty
    stop and Spring shutdown both release it.
  - **Why not disable the sweeper:** abandoned sessions would accumulate on long-running
    servers.
  - **Regression test:** `McpToolServerShutdownTest`. Remove the workaround once the SDK disposes
    its scheduler.

## Coverage

Supported and unsupported feature ids are listed in `McpFeatures` per role, and tests assert
them for the client on both transports and for the server on both transports. Implemented in
both roles:
- version negotiation
- the transport in use
- `tools/list` and `tools/call`
- structured output
- text, image, audio, resource-link, and embedded-resource content
- `resources` (list, read, templates), `resources/subscribe`
- `prompts`, `completion`, `logging`, `progress`

Client only: `tools/list-changed`, `resources/list-changed`, `prompts/list-changed` (the
server's lists are snapshots).

Authorization: the client lists `authorization` as supported when an `HttpAuthorizer` is set;
the server never does (see Authorization).

Not implemented in either role:
- the SSE transport
- cancellation
- sampling, roots, and elicitation

Conformance: the official MCP conformance suite runs against `conformance/mcp-server` and
`conformance/mcp-client` in CI; results and baselined checks are in
`docs/operations/conformance.md` (decision in `protoweft-architecture`).

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
  `HttpClientStreamableHttpTransport`, independent of our client (`SdkClientInteropTest` for
  tools).
- **Resources, prompts, completion, logging, progress** (`AuthoringContract`, run by
  `McpAuthoringHttpTest` and `McpAuthoringStdioTest`): our client against our server on both
  transports. It covers text and binary resources, templates with percent-decoded variables,
  unknown URIs, subscribe → update → unsubscribe, prompts with arguments, embedded resources
  and images, missing arguments, completion (including the 100-value cap), `setLevel`
  filtering of log messages sent during a tool call, progress with and without a token, the
  caller in resource and prompt handlers (`alice` over HTTP, `anonymous` over stdio), and
  annotation-authored entries. A server without resources or prompts answers `UNSUPPORTED`.
- **Conformance fixtures** (`ConformanceFixtureTest`): the SDK's client against our server
  publishing the MCP conformance suite's fixture names (`test://static-text`,
  `test://static-binary`, `test://template/{id}/data`, `test://watched-resource`,
  `test_simple_prompt`, `test_prompt_with_arguments`, `test_prompt_with_embedded_resource`,
  `test_prompt_with_image`, and progress/logging tools). It checks declared capabilities, the
  progress token, and log levels on the wire.
- **Client against SDK servers** (`McpClientNotificationsTest`): every undeclared capability
  fails with `UNSUPPORTED`, subscribing needs `resources.subscribe`, and list-change listeners
  receive the new lists.
- **Authoring API** (`McpAuthoringApiTest`): builder and scan validation, result conversion,
  embedded-resource round trip, percent-decoding.
- **stdio server:** a subprocess running `McpToolServer.stdio`, with the servlet API removed
  from its classpath. It is driven by our stdio client, and tests check that it exits when stdin
  closes and that stray stdout prints don't corrupt the protocol.
- **Manual interop:**
  - Our HTTP client against the TypeScript reference server
    (`@modelcontextprotocol/server-everything streamableHttp`): it negotiated `2025-11-25`,
    listed its tools, called `echo`, and mapped image and resource-link content. This can be
    rerun with `ManualInteropTest` (`PROTOWEFT_MCP_INTEROP_URL`).
  - The MCP Inspector CLI (2.13.0) against our HTTP server, with a bearer token: it listed the
    tools, returned `structuredContent` from `add`, and showed the caller identity from `whoami`.
  - The stdio client against server-everything (stdio) negotiated `2025-11-25`.
