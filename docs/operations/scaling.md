# Horizontal scaling and deployment

Protoweft is a library embedded in your JVM process, not a managed server. It scales the way your
host application is deployed: run several instances behind a load balancer. The only thing Protoweft
asks of you is to keep protocol state where every instance can reach it. This page is the concrete
recipe.

## What is stateful, and what is not

The affinity you need at the load balancer depends on the protocol, because only some of them keep
per-connection state in the serving instance.

| Protocol / role | Per-instance state | Load-balancer requirement |
|---|---|---|
| MCP tool server, Streamable HTTP (**stateful**, default) | SDK session (`Mcp-Session-Id`): open SSE streams, subscriptions, progress | Session affinity, **or** clients that recover on `404` (see below) |
| MCP tool server, **stateless** HTTP | none | None — any instance serves any request |
| MCP stdio | n/a (one child process) | n/a |
| UTCP `http` manual provider | none | None — plain request/response |
| RAP tool server | pending calls + subscriptions, in a **`RapStore`** | None, with a **shared** store (see below) |
| A2A agent server (SSE streaming) | the in-flight stream | Affinity for the life of one stream |
| AG-UI agent server (SSE) | the in-flight run/conversation | Affinity for the life of one run |
| OAuth (`protoweft-oauth`) | tokens, in a **`TokenStore`** | None, with a **shared** store |
| `ToolRegistry`, discovery `CapabilityCatalog` | in-memory, built from your code at startup | None — identical on every replica |

Single-shot request/response traffic (UTCP `http`, stateless MCP, one-shot `tools/call`) needs no
affinity at all. Affinity is only ever about a **long-lived stream** or a **session that outlives one
request**.

## MCP: prefer stateless over stickiness

Sticky sessions are operationally painful (uneven load, drain/rebalance headaches, broken on client
IP changes). For an MCP **tool** server you usually do not need them, because a tool server's whole
surface — tools, resources, prompts, completions — is request/response. Two ways to avoid affinity:

### Option A — stateless MCP transport (no session at all)

Use `McpToolServer.statelessHttp(tools)` instead of `McpToolServer.http(tools)`. It wires the MCP
Java SDK's `HttpServletStatelessServerTransport`: it issues no `Mcp-Session-Id` and holds no session,
so every request is independent and any replica can serve it — **no affinity at all**. It covers
tools, resources, resource templates, prompts and completions. It does **not** carry SSE streaming,
server→client calls (sampling, roots, elicitation), notifications, progress or subscriptions — none
of which a plain tool server needs (a progress- or log-emitting tool still runs; those callbacks are
no-ops).

```java
ProtoweftServer.builder().port(8080)
        .endpoints(McpToolServer.statelessHttp(tools).serverInfo("weather", "1.0"))
        .start();
```

This is independent of the `2026-07-28` wire revision: stateless *operation* is available now on the
`2025-*` revisions through this transport. Its `compliance()` reports `transport/stateless-http` as
supported and `resources/subscribe`, `logging` and `progress` as unsupported.

Use Option A whenever your tools are synchronous and you do not stream progress back to the client.

### Option B — session affinity learned from the response (keep the stateful transport)

If you need the stateful transport (progress notifications, logging to the session, resource
subscriptions), a request carrying an `Mcp-Session-Id` must reach the **exact replica that created
that session** — its state lives only in that replica's memory.

**Plain consistent-hashing of `Mcp-Session-Id` does not achieve this.** `initialize` carries no
session header, so it lands on an arbitrary replica; the opaque id that replica generates then hashes
to *some* replica with no relation to the one that issued it. The client's later requests would route
to the wrong replica and get repeated 404s — and because the id is server-generated, re-initializing
just yields another id that hashes elsewhere, so it doesn't recover either.

What works is **affinity learned from the response**: the proxy records the `id → backend` binding the
first time a replica returns a new `Mcp-Session-Id`, then pins subsequent requests carrying that id to
that backend. HAProxy expresses this directly with a stick-table:

```haproxy
backend protoweft_mcp
    # Pin each MCP session to the replica that created it: learn the id from the
    # response, route later requests carrying it back to the same server.
    stick-table type string len 64 size 100k expire 30m
    stick on req.hdr(Mcp-Session-Id)
    stick store-response res.hdr(Mcp-Session-Id)
    server app-1 app-1:8080 check
    server app-2 app-2:8080 check
    server app-3 app-3:8080 check
```

Equivalents: NGINX Plus `sticky learn` (reading `Mcp-Session-Id` from the upstream response — the
open-source `hash` directive is *not* sufficient, per the above), or Envoy's
`stateful_session` filter with a dedicated override header (not `Mcp-Session-Id` itself, which the
header-based session state would overwrite). The `404` → client re-`initialize` behaviour is a
last-resort safety net for a dropped binding (e.g. a replica restart), not a substitute for affinity.

Given this complexity, **prefer Option A** unless you specifically need the session-scoped features.

## Shared state for multi-instance

Two Protoweft stores are in-memory by default and must be shared when you run more than one instance.

### RAP — share the `RapStore`

RAP is asynchronous: a call is sent, and the result (or event stream) arrives later as a callback
HTTP request to the callback URL. The `RapStore` SPI persists pending calls and subscriptions so an
instance can **resume them after a restart**.

Be precise about what the shared store does and does not do:

- **Restart recovery (what it gives you):** `RapClientConnector.recover()` runs at construction and
  reattaches this owner's stored calls, re-registering their tokens in the new process. So a replica
  that restarts picks up calls it had in flight.
- **Live cross-replica routing (what it does *not* give you):** a callback is dispatched by the
  in-memory `CallbackRouter`, which 404s a token it doesn't hold *without consulting the store*.
  `open()` registers the token only on the replica that made the call, and `recover()` only runs at
  startup — so a *running* replica B will not pick up a call replica A opened just because they share
  a store. **The callback must reach the replica that opened the call.**

The practical consequences: either route callbacks back to the owning replica (give each replica a
callback URL the tool server can reach directly — a per-replica hostname, or LB affinity on the
callback token path `.../callbacks/<token>`), or treat the shared store purely as restart recovery.
Use a shared store (and stable store keys) for durability; do not rely on it alone for live callback
delivery across replicas.

```java
RapStore store = /* your shared impl, e.g. JDBC- or Redis-backed */;

RapToolServer server = RapToolServer.builder(tools)
        .storeKey("orders-rap")      // stable across replicas
        .store(store)
        .build();

RapClientConnector client = RapClientConnector.builder("https://tools.example.com")
        .storeKey("orders-client")   // stable across replicas
        .store(store)
        .build();
```

`InMemoryRapStore` (default) is single-process; `FileRapStore` persists to disk and works across
replicas only on genuinely shared storage. For real horizontal scale, implement `RapStore` over your
database or Redis. Implementations must be thread-safe.

### OAuth — share the `TokenStore`

`TokenStore.inMemory()` is per-process. For multiple instances, implement `TokenStore` over a shared,
encrypted-at-rest backend so a token obtained on one replica is visible to the others.

### Tool registry — no sharing needed

`ToolRegistry` and the discovery `CapabilityCatalog` are built from your code at startup, so every
replica that runs the same build registers the same tools. There is no cross-instance registry sync,
and none is needed; just deploy the same artifact everywhere.

## Reference topology

```
                         ┌──────────────────────────────┐
   clients ──────────▶   │  L7 proxy / LB               │
                         │  • round-robin: stateless     │
                         │    MCP, UTCP http, RAP        │
                         │  • ring-hash Mcp-Session-Id:  │
                         │    stateful MCP               │
                         │  • stream affinity: A2A/AG-UI │
                         └───────────────┬──────────────┘
                     ┌───────────────────┼───────────────────┐
                     ▼                   ▼                   ▼
                ┌─────────┐         ┌─────────┐         ┌─────────┐
                │ app-1   │         │ app-2   │         │ app-3   │
                │ Protoweft         │ Protoweft         │ Protoweft
                └────┬────┘         └────┬────┘         └────┬────┘
                     └───────────────────┼───────────────────┘
                                         ▼
                        ┌────────────────────────────────┐
                        │ shared RapStore + TokenStore     │
                        │ (JDBC / Redis / ...)             │
                        └────────────────────────────────┘
```

## Checklist

- [ ] Stateless MCP transport (`McpToolServer.statelessHttp`), or session affinity **learned from the
      `Mcp-Session-Id` response** (not plain IP/cookie/header hashing).
- [ ] MCP clients recover on `404` by re-initializing — a safety net, not a substitute for affinity.
- [ ] For RAP: callbacks reach the replica that opened the call (per-replica callback URL or affinity
      on the callback path); shared `RapStore` is for restart recovery, not live cross-replica routing.
- [ ] Shared `TokenStore` across replicas if you use OAuth.
- [ ] Same build artifact on every replica (identical tool registry).
- [ ] Stream affinity for A2A / AG-UI SSE, for the life of one stream only.
- [ ] Telemetry: see [Errors and observability](../concepts/errors-and-observability.md); the Spring
      Boot starter bridges to Micrometer, which exports to Prometheus / OpenTelemetry.
