# Embedded Jetty

`agentweave-server-jetty` hosts AgentWeave server endpoints (MCP, A2A, UTCP) in an embedded Jetty
12 server (Jakarta EE 11, Servlet 6.1), for applications that don't run in a servlet container or
Spring Boot.

```java
try (AgentWeaveServer server = AgentWeaveServer.builder()
        .port(8080)
        .authenticator(myAuthenticator)
        .endpoints(McpToolServer.http(tools), UtcpManualProvider.builder(tools).build(), a2aServer)
        .start()) {
    server.join();
}
```

## Builder

| Method | Default | Meaning |
|---|---|---|
| `host(host)` | `localhost` | Interface to bind. Use `0.0.0.0` for all interfaces. |
| `port(port)` | `8080` | Port to bind; `0` picks a free port. |
| `authenticator(authenticator)` | `InboundAuthenticator.ALLOW_ALL` | Runs before every endpoint request. |
| `endpoints(providers...)` | none | Adds every endpoint of each `ServerEndpointProvider` (`McpToolServer.Http`, `A2aAgentServer`, `UtcpManualProvider`). |
| `endpoint(servletEndpoint)` | none | Adds one `ServletEndpoint`. |
| `start()` | — | Binds and starts the server. Fails with `CONFIGURATION` if it can't bind or two endpoints share a name. |

The running server offers `port()` (the bound port, useful with port 0), `baseUrl()`
(`http://<host>:<port>`, with `localhost` for the wildcard host), `join()` (blocks until the server
stops) and `close()`.

Each endpoint is mounted at its URL patterns with an `InboundAuthFilter` in front of it, async
where the endpoint needs it.

!!! warning "Authentication"
    The default authenticator admits everyone. It's safe only while the server binds `localhost`.
    Set `.authenticator(...)` before binding any other interface; see
    [Security and credentials](../concepts/security.md#inbound-inboundauthenticator).

## Several servers in one host

Endpoint names must be unique within a host:

- `McpToolServer.Http` is named `mcp` at `/mcp`, and `mcp:<path>` at any other `endpointPath`.
- `A2aAgentServer` contributes `<name>-card` and `<name>`, where `<name>` is its `endpointName`
  (default `a2a`). Only one A2A server per host can own `/.well-known/agent-card.json`.
- `UtcpManualProvider` is named `utcp` at `/utcp` and `/utcp/*`.

## Port 0 and public URLs

The A2A card and UTCP tool URLs must name the server's public address. When the port is known only
after start, pass a `Supplier`:

```java
AtomicReference<String> base = new AtomicReference<>();
try (A2aAgentServer agent = A2aAgentServer.builder(handler, descriptor).publicUrl(base::get).build();
     AgentWeaveServer server = AgentWeaveServer.builder()
             .port(0)
             .endpoints(McpToolServer.http(tools),
                        UtcpManualProvider.builder(tools).baseUrl(base::get).build(),
                        agent)
             .start()) {
    base.set(server.baseUrl());
    // ...
}
```

The A2A supplier is read when the card is first needed; the UTCP supplier on every manual request.
(Without `baseUrl`, `UtcpManualProvider` derives tool URLs from each request.)

## Shutdown

`close()` stops Jetty. Stopping destroys the endpoint servlets, and the MCP server closes its
sessions and its SDK session-sweeper thread when that happens. `A2aAgentServer` owns background
threads of its own: close it too, as the `try` block above does.
