---
id: agentweave-architecture
type: architecture-design
status: draft
title: AgentWeave architecture — modules, SPIs, compatibility matrix
parent: agentweave-goal
---

# AgentWeave architecture

Realizes the principles in `agentweave-goal`. This spec owns the module boundaries, the core SPIs, the
dependency edges between modules, and the per-protocol compatibility matrix with its sources.

## Drivers

- Keep protocol-neutral concepts in a small core; keep each protocol's types and SDK behind a module
  boundary (goal principles 1, 2, 9).
- Spring Boot support must be optional and removable; other framework integrations must not need it
  (principles 3, 4).
- Compatibility and feature coverage must be explicit and testable, and must never over-claim
  (principles 5, 6).
- SDK version and wire-protocol version are separate axes and must stay separate (principle 8).

## Module layout

```
agentweave-bom                   Version alignment for consumers.                    [not yet built]
agentweave-core                  Protocol-NEUTRAL only, zero dependencies.
agentweave-mcp                   MCP client connector (stdio). Depends on core + MCP Java SDK 2.x. See `agentweave-mcp`.
agentweave-a2a                   A2A connector. Depends on core + a2a-java.           [not yet built]
agentweave-utcp                  UTCP connector. Depends on core; implemented from spec. [not yet built]
agentweave-spring-boot-starter   OPTIONAL. Depends on core + selected connectors.     [not yet built]
examples/mcp-client-example      Runnable demonstration of the MCP client slice.
```

Dependency rule (enforced by module boundaries):

- `core` depends on nothing protocol-specific.
- each connector depends on `core` + exactly its own protocol SDK.
- the Spring starter depends on `core` + connectors; **nothing depends on the starter**, so plain-Java
  and non-Spring-framework users consume `core` + a connector directly.

## Core SPIs and value types (`agentweave-core`)

Protocol-neutral surface, no protocol types leaking out:

- **`ProtocolConnector`** — lifecycle (`start`/`close`), identity (`ProtocolId`), and the negotiated
  `ComplianceReport`. The extension seam every protocol module implements.
- **Tool model** — `ToolSpec` (name, description, JSON input schema as a neutral map), `ToolInvocation`
  (tool name + argument map), `ToolOutcome` (ordered neutral `Content` parts + `error` flag + optional
  structured payload). `Content` is sealed: `Text`, `Data`, and `Unmapped` — unmappable parts are made
  visible, never dropped.
- **`ToolConnector`** (`ProtocolConnector` + `listTools` + `invoke`) — the interface application code
  programs against for tool-calling protocols. A tool that runs and fails is an outcome with `error`
  set, not an exception. (Separate catalog/invoker interfaces were considered and dropped: nothing
  needed one without the other.)
- **`CredentialProvider`** SPI — the authentication/configuration extension point; connectors resolve
  credentials through it rather than hard-coding auth.
- **`ObservationHandler`** — observability hooks fired around discovery/invocation (start/stop/error),
  so metrics/tracing can bind without the core depending on any telemetry library.
- **Error model** — a single unchecked `AgentWeaveException` with a neutral `ErrorCategory` and the
  protocol's native code kept as `protocolCode`; connectors map protocol-specific errors into it.
- **Observability** — `ObservationHandler.observe(...)` is the shared helper every connector uses, so
  all protocols report operations identically; handler exceptions are swallowed.
- **Compatibility model** — `ComplianceReport` = `{ protocol, sdkVersion, wireRevisions[],
  supported[], unsupported[] }`, the machine-readable, test-asserted statement of what a connector
  actually does (principles 5, 6, 8).

## Key decisions

- **MCP has two independent version axes.** The *wire/spec revision* is a date string (`YYYY-MM-DD`)
  negotiated in the `initialize` handshake and marking the last backwards-incompatible change; the
  *Java SDK major version* is SemVer (`io.modelcontextprotocol.sdk`). They are not interchangeable.
  AgentWeave's `ComplianceReport` carries `sdkVersion` and `wireRevisions[]` as distinct fields, never
  one "version". *"MCP V1/V2" is interpreted as SDK major versions (1.x vs 2.x) — the axis that holds
  the real breaking API change and migration guides.* (Confirmed with the user.)
- **Wrap, don't reimplement.** MCP and A2A connectors wrap the official Java SDKs. UTCP has no official
  Java SDK, so its connector is implemented from the specification (recorded as a deviation risk).
- **Bridge Reactor to a blocking-friendly core.** The MCP SDK's public API is Reactive Streams
  (Project Reactor) with a sync facade. AgentWeave's core interfaces are blocking-friendly; the MCP
  connector uses the SDK's `McpSyncClient` facade and keeps Reactor off the core API.
- **No forced unification.** Tool-calling (MCP, UTCP) and agent task/messaging (A2A) stay as distinct
  core surfaces; A2A will add its own interfaces rather than being squeezed into `ToolInvoker`.

## Compatibility matrix

| Protocol | Spec / wire revisions | Official Java SDK | AgentWeave target |
|---|---|---|---|
| **MCP** | `2024-11-05`, `2025-03-26` (Streamable HTTP, OAuth), `2025-06-18` (no JSON-RPC batching, structured output, OAuth Resource Server), `2025-11-25`, `2026-07-28` (stateless) | `io.modelcontextprotocol.sdk:mcp` — `1.x` (legacy `McpSchema`), **`2.x` GA** (tracks `2025-11-25`, Streamable-HTTP-first, SSE deprecated), `2.2.x-M` (modern stateless, `2026-07-28`) | **Built:** client, SDK **2.1.0**, stdio, wire `2024-11-05`…`2025-11-25` (offers `2025-11-25`). Streamable HTTP, SSE, server role: not built |
| **A2A** | `1.0.0` (prior `0.3.0`, `0.2.6`, `0.1.0`); 3-layer: canonical proto data model → abstract operations → bindings | `org.a2aproject.sdk` (a2a-java), Java 17+, client+server | Target: spec **`1.0.0`**, JSON-RPC binding first. Not built |
| **UTCP** | **`1.1`** (`1.0` deprecated, `0.1` legacy); manual at `GET /utcp` with `manual_version` + `utcp_version`; call templates per tool | **none official** (Python, TypeScript, Go only) | Target: spec **`1.1`**, HTTP manual client first (custom implementation). Not built |

## Risks

- **UTCP has no official Java SDK** — the connector must be built and maintained from the spec; UTCP is
  MPL-2.0 (track licensing). Highest-uncertainty connector.
- **MCP moves fast** (2.x just GA, 2.2 in milestones) — pin versions; isolate fully behind `core`.
- **Domain mismatch** between A2A (tasks/messages) and MCP/UTCP (tools) — resist over-unifying the core.
- **Reactor vs blocking** — bridge deliberately via the SDK sync facade.
- **Licenses** — MCP MIT, A2A Apache-2.0, UTCP MPL-2.0; verify transitively before shipping.

## Out of scope

- Sequencing/roadmap (which connector lands when) — tracked in the working plan, not in this spec.
- Agent orchestration, planning, memory, and LLM-calling — see `agentweave-goal` non-goals.

## Sources

Protocol details below were taken from official specifications and reference implementations (not from
memory), on 2025-11-05:

- MCP versioning (date-based wire revisions): <https://spec.modelcontextprotocol.io/specification/versioning>
- MCP `2025-06-18` changelog: <https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-06-18/changelog.mdx>
- MCP Java SDK (versions, transports, architecture): <https://github.com/modelcontextprotocol/java-sdk> and its releases (`v2.0.0` GA, `v2.1.0`, `v2.2.0-M1`; `MIGRATION-1.0.md`, `MIGRATION-2.0.md`)
- Spring AI MCP (Spring Boot integration): <https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html>
- A2A specification `1.0.0` (overview, bindings, agent discovery): <https://a2a-protocol.org/latest/specification/>
- A2A Java SDK: <https://github.com/a2aproject/a2a-java>
- UTCP specification (`1.1` introduction, RFC): <https://www.utcp.io/> and <https://github.com/universal-tool-calling-protocol/utcp-specification>
