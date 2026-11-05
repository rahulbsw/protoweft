# MCP

`agentweave-mcp` speaks the [Model Context Protocol](https://modelcontextprotocol.io) in both roles,
over **stdio** and **Streamable HTTP**, on the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`
`2.1.0`):

- **Client:** `McpClientConnector` exposes a remote MCP server's tools through the core
  `ToolConnector` API, and its resources, prompts, completion, logging, progress and list-change
  notifications through MCP-specific methods.
- **Server:** `McpToolServer` serves any `ToolSource` (`LocalTools`, a `ToolRegistry`, another
  connector) to MCP clients, plus optional `McpResources` and `McpPrompts`, with logging, progress
  and completion.

MCP SDK types never appear in AgentWeave's public API. Resources and prompts are MCP-only concepts,
so their types live in `io.agentweave.mcp`, not in the core.

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
| `authorizer(httpAuthorizer)` | none | Obtains tokens on demand, for example with OAuth. See [Authorization](#authorization). |
| `requestTimeout(duration)` | 20 s | Timeout for connecting, initialization and each request. |
| `clientInfo(name, version)` | `agentweave` `0.1.0` | The `clientInfo` sent in `initialize`. |
| `observations(handler)` | none | Observability hook. |

Without an authorizer, a 401 or 403 from the server becomes `ErrorCategory.AUTHENTICATION` with the
HTTP status as `protocolCode`.

## Using a client

Both transports behave the same once started:

- `start()` runs the `initialize` handshake. The negotiated revision is in
  `compliance().negotiated()`.
- `listTools()` returns the server's tools as `ToolSpec`s. MCP tool annotations and titles become
  [metadata](../concepts/tools.md#metadata); only hints the server set are copied.
- `invoke(invocation)` calls `tools/call`. `CallToolResult.isError` becomes `ToolOutcome.error()`,
  and `structuredContent` becomes the structured payload.
- The client declares no capabilities of its own (no roots, sampling or elicitation handlers).
- Every call checks the capability the server declared, and fails with `UNSUPPORTED` when it's
  missing: `tools` for `listTools` and `invoke`, `resources` for listing and reading resources,
  `resources.subscribe` for `subscribe` and `unsubscribe`, `prompts`, `completions`, and `logging`
  for `setLogLevel`.
- The connector is thread-safe once started.

### Resources, prompts and completion

These methods are on `McpClientConnector` itself, not on the protocol-neutral `ToolConnector`
interface, so declare the variable with the concrete type:

```java
try (McpClientConnector mcp = McpClientConnector.http("https://tools.example.com").build()) {
    mcp.start();

    for (McpResourceInfo r : mcp.listResources()) {
        System.out.println(r.uri() + " " + r.mimeType());
    }
    List<McpResourceContents> readme = mcp.readResource("docs://readme");
    mcp.subscribe("docs://readme", contents -> System.out.println("changed: " + contents.get(0).text()));

    McpPromptResult prompt = mcp.getPrompt("review", Map.of("code", "class A {}"));
    McpCompletion cities = mcp.completeTemplateVariable("weather://{city}/today", "city", "P", Map.of());
}
```

| Method | MCP request | Returns |
|---|---|---|
| `listResources()` | `resources/list` (all pages) | `List<McpResourceInfo>`: `uri`, `name`, `title`, `description`, `mimeType` |
| `listResourceTemplates()` | `resources/templates/list` (all pages) | `List<McpTemplateInfo>` |
| `readResource(uri)` | `resources/read` | `List<McpResourceContents>`: text, or base64 `blob` (`isText()`, `bytes()`) |
| `subscribe(uri, onUpdate)` | `resources/subscribe` | On each `notifications/resources/updated` for the URI, the SDK re-reads the resource and `onUpdate` receives its contents. One listener per URI. |
| `unsubscribe(uri)` | `resources/unsubscribe` | — |
| `listPrompts()` | `prompts/list` (all pages) | `List<McpPromptInfo>`, with `McpPromptArgument`s |
| `getPrompt(name, arguments)` | `prompts/get` | `McpPromptResult`: a description and `McpPromptMessage`s (role plus core `Content` parts) |
| `completePromptArgument(prompt, argument, typed, context)` | `completion/complete` | `McpCompletion`: `values` (at most 100), `total`, `hasMore` |
| `completeTemplateVariable(uriTemplate, variable, typed, context)` | `completion/complete` | `McpCompletion` |

An unknown resource URI (`-32002`), an unknown prompt or a missing required prompt argument fails
with `INVALID_ARGUMENTS`.

### Logging, progress and list changes

```java
mcp.onLog(log -> System.err.println(log.level() + " " + log.logger() + ": " + log.data()));
mcp.onToolsChanged(tools -> System.out.println(tools.size() + " tools now"));
mcp.start();
mcp.setLogLevel(ToolProgress.Level.DEBUG);

ToolOutcome out = mcp.invoke(ToolInvocation.of("importAll", Map.of("count", 3)), null, progress);
```

- **Progress.** `invoke(invocation, caller, progress)` sends a fresh `progressToken` only when
  `progress` isn't `ToolProgress.NOOP`, and forwards the server's `notifications/progress` for that
  call to `progress.progress(done, total, message)`. The caller isn't sent: MCP has no field for
  it. Through a `ToolRegistry`, `registry.invoke(invocation, caller, progress)` passes `progress`
  on to the connector.
- **Logging.** `onLog(listener)` receives every `notifications/message` as an `McpLogMessage`
  (`level`, `logger`, `data`), in arrival order on the transport's thread, so keep it quick. MCP
  doesn't tie log messages to a request, so they go to this connector-level listener rather than to
  a call's `ToolProgress`. `setLogLevel(level)` sends `logging/setLevel`.
- **List changes.** `onToolsChanged`, `onResourcesChanged` and `onPromptsChanged` receive the new
  list the SDK fetches on each `list_changed` notification.

Listeners can be registered before or after `start()`. Progress and log notifications are
delivered in the order they arrived, before the response that follows them.

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
| `requestTimeout(duration)` | 60 s | The longest a `tools/call` (or a resource, prompt or completion handler) may run before it fails with JSON-RPC `-32603`. The call is abandoned, but its thread isn't guaranteed to stop. |
| `resources(mcpResources)` | none | Publishes [resources](#resources-and-prompts), declaring the `resources` capability. |
| `prompts(mcpPrompts)` | none | Publishes [prompts](#resources-and-prompts), declaring the `prompts` capability. |
| `observations(handler)` | none | Observes each `tools/call`, with the tool name as target. |
| `compliance()` | — | What the server implements. |
| `close()` | — | Closes all sessions and stops the SDK's session sweeper thread. Hosts call it when they destroy the servlet. |

- **The tool list is a snapshot.** The server reads `listTools()` once, on the first
  `endpoints()` call (when the host mounts it), and configuration is frozen from then on.
  `McpResources` and `McpPrompts` are immutable once built. No `list_changed` notifications are
  offered. To serve a changed set, build a new server.
- **Caller propagation.** The caller established by the host's `InboundAuthenticator` is passed to
  `ToolSource.invoke(invocation, caller, progress)`, so `@AgentTool` methods with a
  `CallerIdentity` parameter see who is calling. Resource, prompt and completion handlers receive
  it the same way.
- **Capabilities.** The server declares `tools` (`listChanged: false`) and `logging` (SDK `2.1.0`
  adds `logging` to every server). It adds `resources` (`subscribe: true`, `listChanged: false`)
  only when given `McpResources`, `prompts` (`listChanged: false`) only when given `McpPrompts`, and
  `completions` only when at least one completer is registered.

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
`serverInfo`, `requestTimeout`, `resources`, `prompts`, `observations` and `compliance` methods as
`Http`. Any MCP client can launch it, including AgentWeave's: `McpClientConnector.stdio("java",
"-cp", classpath, "com.example.GreeterServer")`.

!!! warning "Keep stdout clean"
    stdout carries JSON-RPC. While `run()` is serving, `System.out` is redirected to `System.err` so
    stray prints can't corrupt the stream, but you must still configure your logging framework to
    write to stderr. The servlet API isn't needed at runtime for stdio serving.

Over stdio the caller is always `CallerIdentity.ANONYMOUS`: the process that launched the server is
the trust boundary.

## Resources and prompts

Build `McpResources` and `McpPrompts` with a builder, by scanning annotated methods, or both, and
hand them to `McpToolServer.http(tools)` or `McpToolServer.stdio(tools)`.

### With builders

```java
McpResources resources = McpResources.builder()
        .resource("docs://readme", "readme", "text/markdown", req -> Files.readString(readme))
        .template("weather://{city}/today", "today", "application/json",
                  req -> forecast(req.variable("city")))
        .completion("weather://{city}/today", "city", McpCompleter.of(List.of("Paris", "Pune")))
        .build();

McpPrompts prompts = McpPrompts.builder()
        .prompt(McpPromptInfo.of("review", "Reviews code",
                        McpPromptArgument.required("code", "The code"),
                        McpPromptArgument.optional("language", "Programming language")),
                req -> "Review this code:\n" + req.argument("code"))
        .completion("review", "language", McpCompleter.of(List.of("java", "kotlin")))
        .build();

McpToolServer.Http mcp = McpToolServer.http(tools).resources(resources).prompts(prompts);
```

- `resource(uri, name, mimeType, handler)` adds a resource at a fixed URI;
  `resource(McpResourceInfo, handler)` also sets a title and description. A URI containing a
  `{variable}` is rejected: use `template`.
- `template(uriTemplate, name, mimeType, handler)` adds a resource template. URI templates are
  RFC 6570 simple `{var}` expressions; the handler reads the percent-decoded values with
  `request.variable(name)`.
- `completion(...)` adds one `McpCompleter` per prompt argument or template variable.
  `McpCompleter.of(list)` suggests the candidates that start with the typed value, ignoring case.
  `build()` rejects a completer for an unknown prompt, template, argument or variable.
- Duplicate URIs, templates, prompt names and completers are rejected with
  `IllegalArgumentException`.

**Handler requests.** `McpResourceRequest` has the `uri`, the template `variables` and the
`caller`. `McpPromptRequest` has the prompt `name`, the `arguments` and the `caller`; required
arguments are checked before the handler runs. `McpCompletionRequest` has the `argument`, the typed
`value`, the `context` (values already chosen for the other arguments) and the `caller`.

**Resource handler results:**

| The handler returns | Contents |
|---|---|
| A `String` | Text, typed `text/plain` unless the resource declares a type |
| A `byte[]` | A base64 blob, typed `application/octet-stream` unless declared |
| An `McpResourceContents`, or a list of them | As returned (`McpResourceContents.text(...)`, `.blob(...)`) |
| Anything else (a record, map or list) | Its JSON as text, typed `application/json` unless declared |

**Prompt handler results:** a `String` (one user message), an `McpPromptMessage`, a list of them,
or an `McpPromptResult`. `McpPromptMessage.user(text)`, `assistant(text)`, `data(role, mimeType,
base64)` and `resource(role, contents)` (an embedded resource) build messages.

### With annotations

```java
public class DocsResources {
    @McpResource(uri = "docs://readme", mimeType = "text/markdown", description = "The project README")
    public String readme() {
        return "# AgentWeave";
    }

    @McpResourceTemplate(uriTemplate = "weather://{city}/today", mimeType = "application/json")
    public Forecast today(String city, CallerIdentity caller) {
        return new Forecast(city, 21.5);
    }

    @McpComplete(uriTemplate = "weather://{city}/today", argument = "city")
    public List<String> cities(String typed) {
        return Stream.of("Paris", "Pune").filter(c -> c.startsWith(typed)).toList();
    }
}

public class ReviewPrompts {
    @McpPrompt(description = "Reviews code")
    public List<McpPromptMessage> review(@ToolParam(description = "The code") String code,
                                         Optional<String> language) {
        return List.of(McpPromptMessage.user("Review this " + language.orElse("") + " code:\n" + code));
    }
}

McpResources resources = McpResources.scan(new DocsResources());
McpPrompts prompts = McpPrompts.builder().scan(new ReviewPrompts()).build();
```

| Annotation | On a public method | Parameters |
|---|---|---|
| `@McpResource(uri, name, title, description, mimeType)` | A resource at a fixed URI | Only `CallerIdentity` and `McpResourceRequest` |
| `@McpResourceTemplate(uriTemplate, name, title, description, mimeType)` | A resource template | One per template variable, bound by name; plus `CallerIdentity`, `McpResourceRequest` |
| `@McpPrompt(name, title, description)` | A prompt | Each parameter is a prompt argument; plus `CallerIdentity`, `McpPromptRequest` |
| `@McpComplete(prompt or uriTemplate, argument)` | The completer of one prompt argument or template variable | One `String` (the typed value) or an `McpCompletionRequest`; returns `List<String>` |

`name` defaults to the method name. Parameters bind by name: compile with `-parameters`, or set
`@ToolParam(name = ...)`. `@ToolParam` also gives a prompt argument its description and
`required` flag; an `Optional` argument is never required. Since MCP prompt arguments and URI
variables are strings, values convert to `String`, `int`, `long`, `double` or `boolean`. Set exactly
one of `prompt` and `uriTemplate` on `@McpComplete`. `McpResources.Builder.scan` and
`McpPrompts.Builder.scan` mix annotated entries with builder entries.

### Subscriptions

The SDK tracks `resources/subscribe` per session. When a resource changes, call
`resources.notifyUpdated(uri)`: every client subscribed to that URI, on every server serving these
`McpResources`, receives `notifications/resources/updated`. Nothing happens when no client is
subscribed, and delivery failures are dropped, so a vanished subscriber can't fail the publisher.

### Completion

The server returns at most 100 values (MCP's cap) with `total` set to the full count and `hasMore`
when truncated. An argument without a completer gets an empty result.

### Handler errors

Handlers run on a worker pool with the server's `requestTimeout`, and are observed as
`resources/read`, `prompts/get` and `completion/complete`.

| From a resource, prompt or completion handler | MCP response |
|---|---|
| `AgentWeaveException` with `INVALID_ARGUMENTS` (including a missing required prompt argument) | JSON-RPC `-32602` |
| Any other `AgentWeaveException` | `-32603` with its message |
| Any other exception | `-32603` `"internal error"` |
| An unknown resource URI | the SDK's `-32002` |
| An unknown prompt | the SDK's `-32602` |

## Progress and logging in tools

A tool reports progress and log messages through [`ToolProgress`](../concepts/tools.md#progress-and-log-messages):
declare a `ToolProgress` parameter on an `@AgentTool` method, or use a `ProgressAwareToolHandler`.

```java
public class Importer {
    @AgentTool(description = "Imports a batch of records")
    public String importAll(@ToolParam(description = "How many records") int count, ToolProgress progress) {
        for (int i = 1; i <= count; i++) {
            progress.progress(i, (double) count, "record " + i);
        }
        progress.log(ToolProgress.Level.NOTICE, "imported " + count + " records");
        return "done";
    }
}
```

On the MCP server, each `tools/call` gets a session-bound `ToolProgress`:

- `progress(done, total, message)` sends `notifications/progress`, only when the request carried
  `_meta.progressToken`, echoing that token.
- `log(level, message)` sends `notifications/message` with the tool name as `logger`.
  `ToolProgress.Level` maps one-to-one onto MCP's RFC 5424 levels, and the SDK filters by the
  session's `logging/setLevel` (default `info`).
- Each notification is sent before `progress` or `log` returns, so it precedes the result on the
  wire. Failures are swallowed: `ToolProgress` never throws.

## Authorization

MCP authorization (revision `2025-11-25`) is OAuth 2.1 for the Streamable HTTP transport. The
OAuth logic lives in [`agentweave-oauth`](../integrations/oauth.md); this module only accepts the
core `HttpAuthorizer` interface, so it doesn't depend on the OAuth module.

### Client

`McpClientConnector.http(..).authorizer(authorizer)` wires the authorizer into every request:

- Before each request it asks the authorizer for an `Authorization` header, which replaces any
  static header of that name.
- On a 401 or 403 it hands the response's `WWW-Authenticate` header to the authorizer, which
  decides whether to retry. The SDK bounds the retries.
- The OAuth resource is the MCP endpoint URL.
- The connector's compliance report lists `authorization` as supported only when an authorizer is
  configured.

`OAuthClient` is the implementation. Machine to machine, with a pre-registered confidential client:

```java
OAuthClient oauth = OAuthClient.builder()
        .grant(OAuthClient.Grant.CLIENT_CREDENTIALS)
        .registration(ClientRegistration.builder().clientId("m2m").clientSecret("s3cret").build())
        .build();

try (McpClientConnector mcp = McpClientConnector.http(baseUrl).authorizer(oauth).build()) {
    mcp.start();   // 401 -> protected-resource metadata -> AS metadata -> token -> retry
    mcp.invoke(ToolInvocation.of("whoami", Map.of()));
}
```

Interactive, where a user signs in in a browser (authorization code with PKCE):

```java
OAuthClient oauth = OAuthClient.builder()                      // AUTHORIZATION_CODE is the default grant
        .registration(ClientRegistration.builder()
                .clientIdMetadataDocument(URI.create("https://app.example.com/oauth/client.json"))
                .build())                                    // else Dynamic Client Registration
        .browserLauncher(BrowserLauncher.system())           // the default
        .build();
McpClientConnector mcp = McpClientConnector.http("https://tools.example.com").authorizer(oauth).build();
```

See [OAuth: client](../integrations/oauth.md#client-oauthclient) for registration, the redirect,
token storage, refresh and step-up.

### Server

Compose `agentweave-oauth`'s resource-server pieces with `McpToolServer.http(...)` on one host:
`ProtectedResourceMetadataEndpoint` publishes the RFC 9728 metadata, and `BearerTokenAuthenticator`
is the host's `InboundAuthenticator`.

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

- The `WWW-Authenticate` challenges (`resource_metadata`, `scope`, `invalid_token`,
  `insufficient_scope`) are sent by the host's `InboundAuthFilter`, because the SDK's header
  validator can only send a bare error.
- The token's subject and scopes reach tools as the `CallerIdentity`; the token itself never does.
- `McpToolServer`'s own compliance report still lists `authorization` as unsupported, because the
  server module can't see what the host composed in front of it. The report never over-claims.

See [OAuth: resource server](../integrations/oauth.md#resource-server) for the verifier, scope
rules and exact challenges. `McpOAuthEndToEndTest` in `agentweave-oauth` runs exactly this setup
with a real client and server.

## Content mapping

| MCP → AgentWeave (client) | |
|---|---|
| text | `Content.Text` |
| image, audio | `Content.Data` (base64 as received) |
| `resource_link` | `Content.Link` |
| embedded `resource` | `Content.Resource` (URI, MIME type, and text or base64 blob) |
| `structuredContent` | `ToolOutcome.structured` |

The same mapping applies to tool results and prompt messages.

| AgentWeave → MCP (server) | |
|---|---|
| `Content.Text` | text |
| `Content.Data` with an `image/*` or `audio/*` type | image or audio |
| `Content.Json` | text holding the serialized JSON |
| `Content.Link` | `resource_link` (the URI doubles as the name when the link has none) |
| `Content.Resource` | embedded `resource` (text or blob contents) |
| `Content.Data` of any other type, `Content.Unmapped` | the call fails with `-32603`: in a tool result MCP could carry them only as embedded resources, which needs an invented URI |
| `structured` | `structuredContent`, only when it is a `Map` (MCP requires a JSON object); no `outputSchema` is declared |

A **prompt message** carries exactly one content part, mapped as above;
`McpPromptMessage.resource(...)` builds an embedded-resource message. A message with more than one
part fails the call with `-32603`.

## Error mapping

**Client.** The JSON-RPC code (or HTTP status) is kept as `protocolCode`.

| MCP failure | `ErrorCategory` |
|---|---|
| `-32602` invalid params (unknown tool, malformed request, and invalid arguments on servers before `2025-11-25`) | `INVALID_ARGUMENTS` |
| `-32002` resource not found | `INVALID_ARGUMENTS` |
| `-32601` method not found | `UNSUPPORTED` |
| HTTP 401 or 403 (that the authorizer, if any, didn't resolve) | `AUTHENTICATION` |
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

Resource, prompt and completion handlers map their errors as described under
[handler errors](#handler-errors).

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
| `content/resource` (embedded resources) | ✓ | ✓ (in prompt messages) |
| `resources` (list, read, templates), `resources/subscribe` | ✓ | ✓ |
| `prompts`, `completion` | ✓ | ✓ |
| `logging`, `progress` | ✓ | ✓ |
| `tools/list-changed`, `resources/list-changed`, `prompts/list-changed` | ✓ | — (lists are snapshots) |
| `authorization` | ✓ only with an `authorizer(...)` | — (compose `agentweave-oauth` in the host; see [above](#server)) |
| `transport/sse` | — | — |
| `cancellation` | — | — |
| `sampling`, `roots`, `elicitation` | — | — |

The server report is static: it lists `resources`, `prompts` and `completion` as implemented even
when a particular server was built without `McpResources` or `McpPrompts`. What a session can use is
what the server declares in `initialize` (see [capabilities](#serving-tools-over-streamable-http)).

The client and HTTP server have been checked against independent implementations: the TypeScript
reference server `@modelcontextprotocol/server-everything` (stdio and Streamable HTTP), the SDK's own
client, and the MCP Inspector CLI. Resources, prompts, completion, logging and progress are tested
client against server on both transports, and the SDK's client is run against a server that
publishes the MCP conformance suite's fixture names.
