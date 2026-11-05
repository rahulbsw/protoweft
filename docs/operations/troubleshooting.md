# Troubleshooting

## Tools

**`scan` fails with "parameter names are not available".**
: Compile with `-parameters` (Gradle: `options.compilerArgs.add("-parameters")`; Maven:
  `<maven.compiler.parameters>true</maven.compiler.parameters>`), or name every parameter with
  `@ToolParam(name = "...")`. Spring Boot's Gradle plugin and `spring-boot-starter-parent` set
  `-parameters` for you.

**`scan` fails with "unsupported tool parameter type".**
: Use a scalar, enum, record, `List`/`Set`/array, `Map<String, T>` or `Optional`. Classes that
  aren't records are not mapped; wrap their fields in a record. See
  [Generated schema](../concepts/tools.md#generated-schema).

**`TOOL_NOT_FOUND` from a `ToolRegistry`.**
: Registry names are `<source>.<tool>`. Check `registry.listTools()` for the exact names. Through
  LangChain4j the model sees sanitized names (`weather__forecast`).

**A tool's `@Transactional` (or other advice) doesn't apply in Spring.**
: `LocalTools` calls the target object directly, so AOP advice is bypassed. Call a separate
  advised bean from the tool method.

## MCP

**An stdio server fails to start or the client reports protocol errors.**
: stdout carries JSON-RPC: anything else printed there corrupts the stream. For servers built with
  `McpToolServer.stdio(...)`, configure your logging framework to write to **stderr** (for example
  `slf4j-simple`, whose default is stderr). `run()` redirects `System.out` while serving, but
  logging set up before that may still hold the original stream.

**`start()` fails with `AUTHENTICATION` before an stdio server launches.**
: Over stdio only `Credential.NamedSecret`s are accepted (as environment variables). A
  `BearerToken` can't be used over stdio.

**`start()` fails with `AUTHENTICATION` against an HTTP server that uses OAuth.**
: The server answered 401 or 403. Supply a token through `credentials(...)`, or let the connector
  obtain one with `authorizer(oauthClient)` (see [MCP authorization](../protocols/mcp.md#authorization)).
  If it still fails with an authorizer, the OAuth flow gave up, for example because the client
  isn't registered, the server doesn't advertise PKCE `S256`, or the retry limit was reached. To
  see the reason, call `oauthClient.handleUnauthorized(resource, wwwAuthenticate, status)` yourself:
  its `TokenResult.failure()` says why.

**`403` from the MCP endpoint.**
: The request carried an `Origin` header that isn't in `allowedOrigins(...)`
  (`agentweave.server.mcp.allowed-origins` in Spring). Browser-based clients, such as the MCP
  Inspector UI, need their origin listed; `:*` matches any port, for example
  `http://localhost:*`.

**`503` with `Retry-After: 1` from `/mcp` in Spring.**
: With `agentweave.server.mcp.expose=registry`, the MCP endpoint is built only once the client
  connectors have started. Retry after startup.

**New tools don't appear over MCP.**
: `McpToolServer` reads the tool list once, when it is first mounted, and doesn't offer
  `tools/list_changed`. Build a new server to publish a changed tool set.

**`listTools`/`invoke` fail with `UNSUPPORTED`: "server did not declare the 'tools' capability".**
: The server offers no tools capability; it may be a resources- or prompts-only server, which
  AgentWeave doesn't support.

**The JVM doesn't exit after stopping an MCP HTTP server.**
: MCP SDK 2.1.0 runs its idle-session sweeper on a non-daemon thread that it never shuts down.
  AgentWeave works around this: `McpToolServer.Http.close()` stops the thread, and hosts call it
  when they destroy the servlet (Jetty `close()`, Spring shutdown). If you called `endpoints()`
  yourself without handing the server to a host, call `close()` on it.

## A2A

**Clients can't reach the agent, or connect to `localhost`.**
: The agent card advertises `publicUrl` + path. In Spring, set `agentweave.server.a2a.public-url`;
  without it the card advertises `http://localhost:<port>` and a WARN is logged. Behind a proxy,
  use the externally visible URL.

**The agent card answers 401.**
: The `InboundAuthenticator` guards the card endpoint too. Admit
  `GET /.well-known/agent-card.json` anonymously if the card should be public (see
  [Security](../concepts/security.md#inbound-inboundauthenticator)), and in Spring Security,
  `permitAll()` it.

**`-32009` from the JSON-RPC endpoint.**
: A2A 1.0 requires the `A2A-Version: 1.0` header. The AgentWeave client and the official SDKs send
  it; hand-written requests must too.

**`start()` fails with `VERSION_MISMATCH` or `UNSUPPORTED`.**
: The agent's card has no JSON-RPC interface (`UNSUPPORTED`), or its interface isn't A2A 1.x
  (`VERSION_MISMATCH`). gRPC and HTTP+JSON bindings and pre-1.0 agents aren't supported.

**"task not found" for a task you just created.**
: Tasks belong to the caller that created them. If the follow-up request is authenticated as a
  different caller (or the first one was anonymous and the second isn't), the task is invisible to
  it.

**Push notifications aren't delivered to a local webhook.**
: The default policy accepts only `https` webhooks on public addresses. Use
  `insecurePushTargets(true)` for development.

**`reply()` throws `IllegalStateException`.**
: `reply` must be the only call of a handler run and can't answer a resumed task. Use
  `complete(message)` to answer an existing task.

## UTCP

**"URL must use HTTPS or be a literal loopback address".**
: Discovery, tool and OAuth2 token URLs must be `https`, or `http` on a literal loopback address
  (`localhost`, `127.0.0.1`, `::1`). There's no switch to allow plain HTTP to other hosts. For
  local development, serve the provider on loopback.

**A manual from a remote host is rejected for loopback URLs.**
: A manual fetched from a non-loopback origin may not declare loopback tool or token URLs. Serve
  both from the same kind of origin, or use `https` names.

**"Variable … not found".**
: Variables are namespaced by the manual name: `${API_KEY}` in manual `weather` looks up
  `weather_API_KEY`, and in `my_api` looks up `my__api_API_KEY`. There's no fallback to the bare
  name. In Spring, `agentweave.utcp.manuals.<name>.variables` takes the bare name and namespaces it
  for you.

**An inline (`text`) manual fails registration with a missing variable such as `schema`.**
: The inline content is variable-substituted, as in the reference client, so `"$schema"` looks
  like a variable. Use a `file` or `http` manual.

**Tools of a text or file manual are missing.**
: By default a manual may only contain tools of its own call-template type. Add
  `allowedCommunicationProtocols("http")`. Check `skippedTools()` for the reason a tool was left
  out.

**Calling an AgentWeave-served UTCP tool with plain arguments fails with 400.**
: AgentWeave's provider publishes the whole argument object as the `body` argument. A UTCP client
  calls it with `{"body": {...}}`; a raw HTTP client posts the argument object itself as the body.

**UTCP tool URLs in the manual point at the wrong host behind a proxy.**
: Set `baseUrl(...)` (`agentweave.server.utcp.base-url`), or in Spring set
  `server.forward-headers-strategy` so request-derived URLs are correct.

## Spring Boot

**POST requests to `/mcp`, `/a2a` or `/utcp/**` get 403 with Spring Security.**
: Spring Security's CSRF protection rejects them. Make it ignore the protocol paths:
  `csrf(c -> c.ignoringRequestMatchers("/mcp", "/a2a", "/utcp", "/utcp/**"))`. See
  [Spring Boot: security](../integrations/spring-boot.md#security).

**No endpoints appear.**
: Servers are opt-in: set `agentweave.server.<protocol>.enabled=true`, add the protocol module, and
  run a servlet web application (`spring-boot-starter-webmvc`).

**Startup fails naming a missing artifact.**
: A protocol is configured but its module isn't on the classpath. Add
  `io.agentweave:agentweave-<protocol>`.

**Remote tools are missing from the `ToolRegistry` at startup.**
: Connectors start after the web server and are added once started. Use the registry from
  `ApplicationReadyEvent`, not `@PostConstruct`.

**A tool bean that injects `ToolRegistry` causes a circular dependency.**
: Inject `ObjectProvider<ToolRegistry>` instead.
