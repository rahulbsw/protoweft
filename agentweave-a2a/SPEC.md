---
id: agentweave-a2a
type: module-design
status: draft
title: agentweave-a2a — A2A client and agent server (JSON-RPC)
parent: agentweave-architecture
---

# agentweave-a2a

## Responsibility

Both A2A roles over the **JSON-RPC binding of A2A spec 1.0**, built on the official a2a-java SDK
(`org.a2aproject.sdk`, pinned to **1.5.0.Final**):

- **Client** — `A2aAgentConnector` lets an application talk to one remote agent through the core
  `AgentConnector` API, and also exposes that agent as a `ToolConnector` with one `ask` tool.
- **Server** — `A2aAgentServer` publishes an AgentWeave `AgentHandler` as an A2A agent with the
  full task lifecycle, as `ServletEndpoint`s that any AgentWeave host mounts.

## Boundary

- Public API: `A2aAgentConnector` (+`Builder`), `A2aAgentServer` (+`Builder`), `A2aFeatures`.
  `internal.JsonRpcTransportMetadata` is public only because `ServiceLoader` requires it.
  Everything else is package-private; SDK types never appear in a public signature.
- Client artifact `a2a-java-sdk-client`; server artifacts `a2a-java-sdk-server-common` and
  `a2a-java-sdk-transport-jsonrpc`, wired with plain constructors (no CDI container, no
  Quarkus/Vert.x reference modules).

## Decisions

- **Versions come from their sources.** Wire revision = the SDK's
  `AgentInterface.CURRENT_PROTOCOL_VERSION` (`1.0`, sent as `A2A-Version`); SDK version = a
  resource generated from `gradle.properties`. The client's negotiated revision is the
  `protocolVersion` of the card's JSON-RPC interface; a different major fails `start()` with
  `VERSION_MISMATCH`, no JSON-RPC interface with `UNSUPPORTED`. A server does not negotiate, so
  its report leaves the negotiated revision empty.
- **Client sending.** `send(message)` is a blocking `SendMessage`; if the server answers before
  the task settles, the client polls `GetTask` until it is terminal or interrupted. `send(message,
  events)` streams only when the builder allows it and the card declares `streaming`; otherwise it
  delivers no events. The builder `timeout` bounds connect and every wait (`TIMEOUT`).
- **Credentials** go through an own `ClientCallInterceptor` (the SDK's `AuthInterceptor` acts only
  on card-declared schemes) and on the card fetch: `BearerToken` → `Authorization: Bearer`,
  `NamedSecret` → header `name: value`, resolved per call.
- **`ask` tool.** Input `{message (required), task_id?, context_id?}`; description = agent
  description + skills. Text = reply text (or a note naming the waiting state); non-text parts are
  kept. Task replies carry structured `{task_id, context_id, state}` (state lower-case hyphenated,
  e.g. `input-required`) so a caller can answer. `failed`, `rejected`, `canceled` → `error=true`.
- **Error mapping** (native code kept as `protocolCode`):

  | SDK failure | Category |
  |---|---|
  | HTTP 401/403 | `AUTHENTICATION` |
  | other HTTP status | `CONNECTION` while starting, else `PROTOCOL` |
  | `-32009` version not supported | `VERSION_MISMATCH` |
  | `-32001` task not found, `-32602`, `-32005` | `INVALID_ARGUMENTS` (unknown and not-owned tasks are indistinguishable by design) |
  | `-32601`, `-32004`, `-32003`, `-32007`, `-32008` | `UNSUPPORTED` |
  | I/O failure | `CONNECTION` |
  | anything else | `PROTOCOL` |

- **Content mapping** (both directions): `TextPart` ↔ `Text`; `FilePart` bytes ↔ `Data`
  (base64 as on the wire, missing media type → `application/octet-stream`); `FilePart` URI ↔
  `Link`; `DataPart` ↔ `Json`; anything else → `Unmapped` (outgoing `Unmapped` is rejected).
- **Agent card.** Interface URL = public base URL + JSON-RPC path (default `/a2a`). The URL may be
  a `Supplier`, resolved when the card is first needed, so hosts can bind port 0. Default modes are
  `text/plain` and `application/json` (what `Text`/`Json` content produce); override when the
  handler emits files. The card declares no `securitySchemes`: authentication is the host's
  `InboundAuthenticator`. That filter also guards the card endpoint, so an authenticator that
  demands credentials should admit `/.well-known/agent-card.json` anonymously if the card is public.
- **Task ownership.** A task belongs to the `CallerIdentity` name that created it (claimed when
  the handler starts, so the creator can cancel or subscribe during a blocking send); only that
  caller can read, continue, cancel, subscribe, or configure push for it. Others — and unrecorded
  tasks — get "task not found" (`-32001`), as the spec requires for inaccessible tasks. Anyone the
  authenticator admits may create tasks; all anonymous callers share one identity.
- **Extended card** is served by `GetExtendedAgentCard` to non-anonymous callers only; anonymous
  callers get HTTP 401 with `WWW-Authenticate`, like the auth filter (spec §13.3: it MUST require
  authentication).
- **Handler adapter.** The first task call creates the task as a `Task` event carrying the request
  message in its history (the SDK's event-bus `TaskManager` records no initial message otherwise).
  `reply` must be the only call on a new task. A handler that returns without finishing or pausing
  is completed; one that throws fails with the exception message. Cancel sets `isCancelled()`,
  calls `onCancel`, and emits `canceled`; later emissions of that run are ignored.
  `AgentRequest.history` excludes the message being delivered.
- **Push notifications** use the SDK's `BasePushNotificationSender` with its default URL policy
  (`https`, public addresses); `insecurePushTargets(true)` allows `http` and private hosts for
  development.
- **Threads and shutdown.** Agent handlers run on a cached daemon pool (or the caller's executor);
  SDK event consumers and blocking JSON-RPC requests run on a separate unbounded pool, keeping
  container threads free. `DefaultRequestHandler` starts the SDK's event-bus thread itself and its
  `stop()` is package-private, so `close()` interrupts the thread it saw start (matched by the SDK's
  thread name); if a future SDK renames it, that daemon thread leaks until JVM exit.
- **SDK behaviors kept, not patched.** State is in memory only. Streams stay open while a task is
  `input-required`/`auth-required` (the JSON-RPC binding closes streams on terminal states only);
  the client returns the interrupted reply but cannot close that SSE connection — it ends with the
  task. A push config embedded in a blocking `SendMessage` is stored after the first event, so a
  fast agent's earlier events are not pushed; streaming sends and
  `CreateTaskPushNotificationConfig` register before events flow.

## Coverage

Feature ids and the supported/unsupported sets are in `A2aFeatures`; tests assert them.
Client: JSON-RPC, card + extended card, send, streaming send, get, cancel, subscribe, multi-turn,
all four part kinds, static credentials. Server: the same plus list tasks and push notifications
(streaming, push and extended card follow the builder). Not implemented: gRPC and HTTP+JSON
bindings, card signatures, card `securitySchemes` (OAuth/OIDC/mTLS flows), extensions,
multi-tenancy, client-side push configuration and task listing, persistent task stores.

## Verification

- Tests run our client against our server in embedded Jetty (`agentweave-server-jetty`): reply,
  task with artifacts, streamed event order, input-required → resume (blocking and streaming),
  auth-required, cancel, resubscribe, failure/reject/auto-complete, ownership isolation, extended
  card for authenticated callers only, inbound 401, the `ask` tool, error mapping, compliance
  reports, and that `close()` stops the event-bus thread.
- Raw HTTP tests prove the wire format without the SDK client: card JSON shape, `SendMessage`
  results, `A2A-Version` enforcement (`-32009` without it), SSE framing, and push delivery to a
  webhook (embedded config on a streaming send; `CreateTaskPushNotificationConfig` on a paused task).
- Manual interop: the official Python `a2a-sdk` 1.3.0 client read the card and completed
  message, task, multi-turn (blocking) and streaming exchanges against this server. Its streaming
  call on an `input-required` task waited for the stream to close and timed out (see above).

## Sources

Taken from the SDK sources and the specification, not from memory:

- a2a-java `v1.5.0.Final`: <https://github.com/a2aproject/a2a-java/tree/v1.5.0.Final> —
  `client/base`, `transport/jsonrpc` (`JSONRPCHandler`), `server-common`
  (`DefaultRequestHandler`, `AgentEmitter`, `TaskAuthorizationProvider`), and the reference
  `reference/jsonrpc/.../A2AServerRoutes.java` whose request dispatch and `convertToProto` the
  servlet mirrors.
- A2A specification 1.0: <https://a2a-protocol.org/v1.0.0/specification> (§3.1 operations,
  §3.6 versioning, §5.3 method names, §5.4 error codes, §9 JSON-RPC binding, §13 security).
