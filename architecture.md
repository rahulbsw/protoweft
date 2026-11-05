---
id: agentweave-architecture
type: architecture-design
status: draft
title: AgentWeave architecture — modules, SPIs, compatibility matrix
parent: agentweave-goal
---

# AgentWeave architecture

Realizes the principles in `agentweave-goal`. This spec owns the module boundaries, the core
seams, the dependency edges between modules, the cross-module decisions, and the per-protocol
compatibility matrix. Each module's own decisions live in its `SPEC.md`, linked by id.

## Drivers

- Keep protocol-neutral concepts in a small core, and each protocol's types and SDK behind a
  module boundary (goal principles 1, 2, 9).
- Spring Boot support must be optional and removable; other framework integrations must not need
  it (principles 3, 4).
- Compatibility and feature coverage are explicit, tested, and never over-claimed (principles
  5, 6). SDK version and wire revision stay separate axes (principle 8).
- Every protocol works in both directions, client and server, behind one hosting contract.

## Module layout

| Layer | Module | Role | Spec |
|---|---|---|---|
| Core | `agentweave-core` | Protocol-neutral API; no dependencies | (this spec) |
| Hosting | `agentweave-server` | Servlet hosting contract: `ServletEndpoint`, `ServerEndpointProvider`, `InboundAuthFilter` | (this spec) |
| | `agentweave-server-jetty` | Embedded Jetty 12 host for plain Java | (this spec) |
| Protocols | `agentweave-mcp` | MCP client and tool server, over stdio and Streamable HTTP (MCP Java SDK 2.1.0) | `agentweave-mcp` |
| | `agentweave-a2a` | A2A client and full-lifecycle agent server, JSON-RPC binding (a2a-java 1.5.0.Final) | `agentweave-a2a` |
| | `agentweave-utcp` | UTCP client and manual provider, implemented from spec 1.1 | `agentweave-utcp` |
| Security | `agentweave-oauth` | Protocol-neutral OAuth 2.1: an `HttpAuthorizer` client and resource-server pieces (Nimbus) | `agentweave-oauth` |
| Discovery | `agentweave-discovery` | Capability catalog, hybrid search, fallback, sessions, generic meta-tools, raw provider payloads | `agentweave-discovery` |
| | `agentweave-discovery-embeddings` | Offline and LangChain4j-backed `Embedder`s | `agentweave-discovery-embeddings` |
| | `agentweave-discovery-anthropic` | Typed Anthropic tool-search adapter | `agentweave-discovery-anthropic` |
| | `agentweave-discovery-openai` | Typed OpenAI Responses tool-search adapter | `agentweave-discovery-openai` |
| Integrations | `agentweave-langchain4j` | Tools in, agents out, `@Tool` out, catalog tool search | `agentweave-langchain4j` |
| | `agentweave-spring-ai` | Tools as `ToolCallback`s, catalog as `ToolIndex`, `ChatClient` as A2A agent, Spring AI tools out | `agentweave-spring-ai` |
| | `agentweave-argi` | ARGI 2.1.0-RC1: tools in, catalog tool selection, agents out with human-in-the-loop | `agentweave-argi` |
| | `agentweave-argi-neutral` | ARGI's unpublished Spring-free API at a pinned commit; built only with `-PargiSnapshot`, never published | `agentweave-argi-neutral` |
| | `agentweave-spring-boot-starter` | Spring Boot 4 auto-configuration; every protocol optional | `agentweave-spring-boot-starter` |
| Packaging | `agentweave-bom` | Version alignment (`java-platform`) | — |
| Not published | `examples/*`, `evals/tool-discovery` | Runnable examples; the discovery evaluation harness | `evals-tool-discovery` |
| | `conformance/*` | Apps under test for the official MCP conformance suite and the A2A TCK | (this spec) |
| | `templates/*`, `jbang/` | Copyable starter projects (separate Gradle builds) and a JBang demo | — |

Dependency rules, enforced by module boundaries:

- `core` depends on nothing.
- `server` depends on `core` and on the servlet API (compileOnly). Only `server-jetty` brings a
  container.
- Each protocol module depends on `core`, on `server` (for its endpoints), and on exactly its own
  protocol SDK (or none, for UTCP).
- `discovery` depends on `core` only. Its embedding and provider modules add one library each.
- `oauth` depends on `core`, `server` and Nimbus. Protocol modules depend only on core's
  `HttpAuthorizer` and `InboundAuthenticator`, so MCP users who don't use OAuth never get Nimbus.
- Integrations depend on what they wire. No protocol, discovery or core module depends on an
  integration, so plain-Java and non-Spring users take `core` plus the modules they need. The one
  integration-to-integration edge is `argi` → `spring-ai`, because ARGI 2.1.0-RC1's tool API *is*
  Spring AI's `ToolCallback`.

## Core seams (`agentweave-core`)

- **Connectors.** `ProtocolConnector` (lifecycle, `ProtocolId`, `ComplianceReport`) is the seam
  every protocol implements. `ToolConnector` adds `ToolSource` for tools, and `AgentConnector`
  adds message, task and stream operations for agents. Tool-calling and agent messaging stay
  separate surfaces.
- **Tools.**
  - `ToolSource` (`listTools`, `invoke`, caller-aware `invoke`) is the one interface servers,
    adapters and the registry consume.
  - `ToolSpec` carries the name, description, JSON input schema, and protocol-neutral `metadata`:
    behavior hints, title and tags. MCP tool annotations and UTCP tags map to and from it.
  - `ToolOutcome` holds ordered `Content` (`Text`, `Data` with an optional file name, `Json`,
    `Link`, `Resource` — a URI delivered with its text or bytes, like an MCP embedded resource —
    or `Unmapped`, so unmappable parts are visible rather than dropped), an `error` flag, and
    structured content. A tool that runs and fails is an outcome, not an exception. Adapters that
    hand results to a model as text include a text `Resource`'s body after its URI and replace
    binary `Data`/`Resource` bodies with a marker.
  - `ToolRegistry` combines sources under `<source>.<tool>` names.
  - `LocalTools` turns `@AgentTool` methods, or programmatic handlers, into tools, generating JSON
    Schema from the Java types.
  - `ToolProgress` (progress and log messages from a running tool) is injected into `@AgentTool`
    parameters like `CallerIdentity` and passed by `ToolSource.invoke(invocation, caller,
    progress)`; `ProgressAwareToolHandler` is its programmatic form. Protocols without progress
    get `ToolProgress.NOOP`.
- **Agents.** `AgentHandler` with `AgentTaskContext` (reply; working; artifact; require input or
  auth; complete, fail or reject; cancellation) is the server-side contract. `AgentMessage`,
  `AgentReply`, `AgentEvent`, `AgentDescriptor` and `TaskState` are the neutral data model. A2A is
  its first protocol.
- **Cross-cutting.**
  - `CredentialProvider` supplies outbound credentials. `HttpAuthorizer` is the richer outbound
    seam for flows that react to a challenge (headers per request, a 401/403 handler that may
    retry); `agentweave-oauth`'s `OAuthClient` implements it.
  - `InboundAuthenticator` returns an `AuthDecision` for each inbound request; a `Deny` may carry
    a `WWW-Authenticate` challenge, which `InboundAuthFilter` sends. `CallerIdentity`
    flows to tools and agent handlers; its `scopes` attribute drives discovery permissions.
  - `ObservationHandler.observe(...)` is shared by every module; handler failures are swallowed.
  - There is one unchecked `AgentWeaveException`, with a neutral `ErrorCategory` and the native
    `protocolCode`.
  - `ComplianceReport` lists the protocol, SDK name and version, wire revisions, negotiated
    revision, and supported and unsupported features. Tests assert it.

## Cross-module decisions

- **MCP has two version axes.** The wire revision is a date negotiated in `initialize`; the Java
  SDK version is SemVer. "MCP V1/V2" means SDK majors 1.x and 2.x (confirmed with the user).
  Reports keep the two in separate fields.
- **Wrap, don't reimplement.** MCP and A2A wrap the official Java SDKs. UTCP has none, so it is
  implemented from the spec, with the official Python implementation as the authority where the
  docs disagree.
- **One hosting contract.** Every server role is a set of async-capable servlets behind
  `InboundAuthFilter`. Jetty and Spring Boot mount the same endpoints, so auth and caller
  propagation behave the same everywhere. SDK resources are released on servlet `destroy()`.
- **JSON.** Jackson 3 (`tools.jackson`) is used where AgentWeave needs JSON, matching MCP SDK 2.x
  and Spring Boot 4. SDKs keep their own JSON internally (Gson in a2a-java, Jackson 2 in the
  Anthropic SDK); different packages mean no conflict.
- **No model calls.** AgentWeave never calls an LLM. Discovery's provider adapters build request
  parts and process responses. LangChain4j, Spring AI and ARGI remain the model and agent-runtime
  layers (goal non-goal); their adapters hand AgentWeave tools to the framework's own model call.
- **Authorization is a servlet filter, OAuth a separate module.** MCP SDK 2.1.0's
  `ServerHttpHeaderValidator` can only `sendError`, so it cannot set `WWW-Authenticate`; inbound
  auth therefore stays in `InboundAuthFilter` for every protocol. OAuth uses Nimbus
  (`oauth2-oidc-sdk`, `nimbus-jose-jwt`) in `agentweave-oauth`, protocol-neutral so A2A and UTCP can
  reuse it. A server's compliance report never lists `authorization` itself, because the protocol
  module cannot see what the host composed in front of it.
- **Conformance is external evidence, gating CI.** `conformance/*` apps run the official MCP
  conformance suite (`@modelcontextprotocol/conformance`, server and client) and the A2A TCK in
  `.github/workflows/conformance.yml`. Each suite has a baseline (`conformance/*-baseline.yml`)
  whose entries name an unsupported feature id or a precise bug; an unexpected failure or a stale
  entry fails the job. Results and every baselined item are published in the docs. The suites
  found two real gaps that unit tests had missed — embedded resources in MCP tool results and
  inline A2A file names — which led to `Content.Resource` and `Data.name`.
- **Release only through the gate.** `com.vanniktech.maven.publish` publishes the `agentweave-*`
  libraries and the BOM (never examples, evals, conformance apps or `argi-neutral`), signed, to
  Maven Central, from `.github/workflows/release.yml` behind the protected `maven-central`
  environment. The release attaches a CycloneDX SBOM of the libraries' runtime dependencies and
  build-provenance attestations. Every dependency and plugin is checked against
  `gradle/verification-metadata.xml` (SHA-256 and PGP). Owner-only steps are in `RELEASING.md`.
- **Spring Boot 4.x only.** 3.5 is out of OSS support and uses Jackson 2. Server endpoints are
  opt-in per protocol.
- **Claims follow measurement.** Retrieval quality and model-in-the-loop effects of discovery are
  reported only as measured by `evals/tool-discovery`, bound to a commit (see
  `agentweave-discovery`).

## Compatibility matrix

| Protocol | Spec / wire revisions | Official Java SDK | AgentWeave |
|---|---|---|---|
| **MCP** | `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`, `2026-07-28` (stateless) | `io.modelcontextprotocol.sdk:mcp` 1.x, **2.x** (2.1.0 latest GA), 2.2.x milestones | Client and tool server on **SDK 2.1.0**. stdio speaks `2024-11-05`…`2025-11-25`; Streamable HTTP speaks `2025-03-26`…`2025-11-25`; both offer `2025-11-25`. Tools, resources (templates, subscriptions), prompts, completion, logging and progress on both roles; OAuth authorization on the client and, via `agentweave-oauth`, as a resource server. Not built: the SSE transport, sampling, roots, elicitation, `2026-07-28`. SSE stream resumption fails in SDK 2.1.0 (upstream bug) |
| **A2A** | `1.0` (prior `0.3.0`, `0.2.6`, `0.1.0`) | `org.a2aproject.sdk` **1.5.0.Final** (spec 1.0) | Client and full-lifecycle server over JSON-RPC; A2A TCK `1.0.0.alpha3` MUST level passes with an empty baseline. Not built: gRPC and HTTP+JSON bindings, card signatures, multi-tenancy |
| **UTCP** | **`1.1`** (`1.0` manuals accepted) | none (Python, TypeScript and Go only) | Client (http, text and file manuals; http tools; api_key, basic and oauth2 auth) and manual provider. Not built: YAML, OpenAPI conversion, non-HTTP tool templates |

Frameworks: LangChain4j 1.23.0, Spring AI 2.0.2, ARGI 2.1.0-RC1 (plus ARGI main at
`a869538` for `agentweave-argi-neutral`), Spring Boot 4.1.2.

Model providers, for discovery's native adapters: Anthropic tool search (`defer_loading` plus
`tool_reference`; anthropic-java 3.5.0) and OpenAI Responses `tool_search` (openai-java 4.100.0,
`gpt-5.4` and later). Gemini, Bedrock Converse and Mistral have no native tool search, so they use
the generic meta-tools.

## Risks

- UTCP is implemented from a spec whose docs and reference implementation disagree.
  Deviations are tracked in `agentweave-utcp`.
- SDKs move fast. Versions are pinned, and SDK workarounds live in module specs; for example, the
  MCP session-sweeper thread leak is documented in `agentweave-mcp`.
- LangChain4j's tool-search API is `@Experimental`.
- ARGI is a release candidate, and its Spring-free API exists only on main; `agentweave-argi-neutral`
  tracks a pinned commit and stays unpublished until ARGI publishes it.
- The MCP conformance suite is an alpha (`0.2.0-alpha.12`); upgrading it may change scenarios and
  needs a baseline review.
- Dependency verification makes every dependency change regenerate
  `gradle/verification-metadata.xml` (procedure in `CONTRIBUTING.md`).
- Licenses: AgentWeave is MIT. The dependencies are MCP SDK (MIT), a2a-java (Apache-2.0), Nimbus
  (Apache-2.0), Spring AI and ARGI (Apache-2.0) and the Anthropic and OpenAI SDKs
  (MIT/Apache-2.0). The UTCP specification is MPL-2.0; it is implemented
  from the docs, and no code is copied. ToolRet evaluation data carries no license tag, so it is
  downloaded locally only.

## Out of scope

- Sequencing and roadmap, which are tracked in the working plan.
- Agent orchestration, planning, memory and LLM calls (see the `agentweave-goal` non-goals).

## Sources

Taken from official specifications and reference implementations on 2026-11-05:

- MCP:
  - [Versioning](https://spec.modelcontextprotocol.io/specification/versioning)
  - [Java SDK](https://github.com/modelcontextprotocol/java-sdk), its releases (`v2.0.0`,
    `v2.1.0`, `v2.2.0-M2`) and its `MIGRATION-1.0.md` / `MIGRATION-2.0.md`
  - [Authorization (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization),
    RFC 9728 (protected resource metadata), RFC 8414, RFC 8707
  - [Conformance suite](https://github.com/modelcontextprotocol/conformance) `0.2.0-alpha.12`
- A2A:
  - [TCK](https://github.com/a2aproject/a2a-tck) tag `1.0.0.alpha3`
  - [Specification 1.0](https://a2a-protocol.org/latest/specification/)
  - [a2a-java](https://github.com/a2aproject/a2a-java), at `v1.5.0.Final`
- UTCP:
  - [utcp.io](https://www.utcp.io/)
  - [Specification repository](https://github.com/universal-tool-calling-protocol/utcp-specification)
  - [python-utcp](https://github.com/universal-tool-calling-protocol/python-utcp) at `bb8e2f6`
- Tool search:
  - [Anthropic](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
  - [OpenAI](https://developers.openai.com/api/docs/guides/tools-tool-search)
- Frameworks:
  - [Spring AI](https://github.com/spring-projects/spring-ai) 2.0.2 and
    [spring-ai-a2a](https://github.com/spring-ai-community/spring-ai-a2a)
  - [ARGI](https://github.com/agentic-ai-java/argi) 2.1.0-RC1 and main at `a869538`
- Spring Boot:
  - [Support generations](https://api.spring.io/projects/spring-boot/generations)
  - [Developing auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
