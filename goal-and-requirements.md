---
id: protoweft-goal
type: goal-and-requirements
status: draft
title: Protoweft — interoperable agent & tool protocols for Java
---

# Protoweft

*The protocol backbone for Java agents: MCP, A2A, UTCP, RAP and AG-UI behind one protocol-neutral API,
in both directions, plugged into LangChain4j, Spring AI, ARGI, Spring Boot or plain Java.*

## Goal

Be the go-to Java layer for agent interoperability: with tools, with other agents, and with the
user-facing applications people use agents through. The way LangChain became the go-to layer for
composing LLM applications. The scope is protocols, not model orchestration.

Java applications speak the emerging interoperability protocols through one protocol-neutral
programming model instead of coupling to any one protocol's SDK and wire format:

- **Consume** tools and agents over whichever protocol the other side speaks.
- **Publish** their own tools and agents, including to user-facing frontends.
- **Discover** the right capability among thousands without flooding the model's context.

A plain Java app, a bespoke agent framework, a LangChain4j, Spring AI or ARGI agent, or a Spring Boot
service all use the same stable interfaces.

## Problem

Several overlapping standards are converging on how applications expose and consume AI tools and
agents:

- **Model Context Protocol (MCP)** — tool/resource/prompt access over JSON-RPC (stdio, SSE,
  Streamable HTTP).
- **Agent2Agent (A2A)** — agent-to-agent task/message exchange over JSON-RPC, gRPC, or REST.
- **Universal Tool Calling Protocol (UTCP)** — direct, "no wrapper server" tool calls described by a
  published manual.
- **Reactive Agent Protocol (RAP)** — the Hydro project's (AWS and UC Berkeley) fire-and-forget
  tool calls over HTTP, whose results, subscription events and user prompts are posted back to a
  callback URL, so a waiting agent holds no connection.
- **Agent–User Interaction protocol (AG-UI)** — the agent ↔ user-facing-application layer: one run
  input in, one ordered stream of typed events out (text, tool calls, reasoning, shared state,
  progress), as frontends such as CopilotKit and agent frameworks such as LangGraph, CrewAI and Google
  ADK speak it.

Today a Java team wanting more than one of these must pull in each protocol's SDK directly (and for
UTCP, RAP and AG-UI, there is no official Java SDK at all), learn several different domain models, and scatter
protocol-specific types, auth, and error handling through their application. Version confusion makes
it worse: MCP alone carries two independent version axes — the date-based wire revision and the Java
SDK major version — that are routinely conflated. There is no neutral Java seam that lets an
application target "a tool" or "an agent" and swap the protocol underneath.

Scale adds a second problem. An application connected to many servers and manuals has more tools than
it should send to a model on every turn: every schema costs context tokens, and selection gets harder
as the list grows. Java frameworks offer tool search only for their own tools and their own model
clients. None does it across protocols with permission and availability filtering.

## Target users

- **Java application developers** adding tool/agent connectivity to an existing service or CLI, or
  putting a Java agent behind a web frontend.
- **Agent-framework authors** who want protocol connectors they can embed without inheriting a
  Spring (or any other framework) dependency.
- **Spring Boot developers** who want the same capabilities as idiomatic auto-configured beans.

## Principles

These are durable design commitments; every change is held against them.

1. **Protocol-neutral core.** Shared concepts live in one small core module with no protocol-specific
   types in its public API.
2. **One module per protocol, behind stable interfaces.** Each protocol integration is a separate
   module implementing core SPIs; applications depend on the core contract, not the protocol SDK.
3. **Spring is optional and separate.** Spring Boot support is an isolated starter module; the core
   and every protocol connector are fully usable with plain Java and other agent frameworks without
   it.
4. **Framework-neutral extensibility.** Adding an integration for another Java agent framework must
   not require depending on Spring Boot.
5. **Compatibility is explicit and testable.** Each connector publishes a machine-readable report of
   the protocol, SDK version, and wire revisions it targets, plus which features it supports — and
   tests assert it.
6. **Honest compliance.** Protoweft never claims support for a protocol feature it has not
   implemented; unsupported features are declared, not silently ignored.
7. **No invented protocol behavior.** Behavior follows the official specifications and reference
   implementations; any deviation is recorded in the owning spec.
8. **SDK version ≠ wire version.** Where a protocol has independent SDK and wire-format version axes
   (notably MCP), Protoweft models them as separate, explicit fields and never collapses them.
9. **Protocol-specific types stay out of the shared core API** unless genuinely needed by more than
   one integration.
10. **Both directions.** Every supported protocol has a client and a server role, behind one hosting
    contract and one inbound-authentication hook.
11. **No model calls.** Protoweft never calls an LLM. Where a model provider has a native feature
    (such as tool search), Protoweft builds the request parts and the application or its framework
    sends them.
12. **Claims follow measurement.** Effects on model behavior, such as tool-selection accuracy with
    discovery, are stated only as measured by the evaluation harness, bound to a commit, never
    assumed.

## Capabilities

What Protoweft provides:

- **A protocol-neutral core:**
  - tool sources, connectors and a namespaced tool registry
  - `@AgentTool` methods with JSON Schema generated from Java types
  - a neutral agent model covering the full task lifecycle
  - credentials, inbound authentication, observability and one error model
- **Protocol modules, each with client and server:**
  - **MCP**, over stdio and Streamable HTTP: tools, resources (with templates and subscriptions),
    prompts, completion, logging and progress, plus OAuth authorization as a client
    (machine-to-machine and interactive) and as a protected resource server.
  - **A2A**: an agent client, plus a full-lifecycle agent server (tasks, streaming, input-required,
    cancel, resubscribe, push notifications, extended card).
  - **UTCP**: a manual client, plus a manual provider for the app's own tools.
  - **RAP**: a client that receives results on a mounted or built-in callback endpoint, and a
    tool server for the app's own tools, with subscriptions (event streams), OAuth and user-choice
    prompts, cancellation, thread closure, and state that can survive a restart.
  - **AG-UI**: an agent server that serves any agent to AG-UI frontends (every event family, shared
    state, interrupts, the frontend's own tools, opt-in CORS) over both HTTP bindings (SSE, and
    Protobuf when the application adds protobuf-java), and a client that holds AG-UI
    conversations with remote agents and can offer them Protoweft tools, running only read-only ones
    without the application's approval.
- **Hosting:** the same endpoints in embedded Jetty or Spring Boot, each behind the same
  authentication hook.
- **Tool discovery:**
  - A provider-neutral capability catalog: hybrid keyword and semantic search; exact filters for
    protocol, version, permissions and availability; a low-confidence fallback; per-conversation
    sessions.
  - Generic search meta-tools for any model.
  - Native tool-search request parts for Anthropic and OpenAI.
- **Integrations:**
  - **LangChain4j:** Protoweft tools in AI services, AI services served as A2A and AG-UI agents,
    `@Tool` methods served over MCP and UTCP, and catalog-backed tool search.
  - **Spring AI:** Protoweft tools as `ToolCallback`s, the catalog as Spring AI's tool-search
    index, a `ChatClient` served as a streaming A2A or AG-UI agent (over AG-UI with the frontend's
    own tools), and Spring AI tools served over MCP and UTCP.
  - **ARGI:** Protoweft tools in ARGI agents, catalog-based tool selection without an extra model
    call, and ARGI agents served over A2A and AG-UI with human-in-the-loop approvals as
    `input-required` or AG-UI interrupts.
  - **Spring Boot 4 starter:** connectors from configuration, opt-in endpoints, Spring Security,
    Micrometer and Actuator, and opt-in Spring AI wiring.
- **Explicit, tested compatibility reporting** per connector, checked in CI against the official
  MCP conformance suite and the A2A TCK, with every expected failure tied to a declared
  unsupported feature or a named bug. For AG-UI, which has no official suite: an AG-UI conformance
  checker anyone can run against any agent, and interop with the official TypeScript and Python
  SDKs.
- **An evaluation harness** that measures discovery against an all-tools baseline.
- **Runnable examples, starter templates, a JBang demo** and a documentation site.

## Non-goals

- **Not an agent runtime, orchestration engine or LLM client.** Protoweft is the protocol layer, not
  a planner, memory store, prompt-chaining library or RAG framework. LangChain4j, Spring AI and ARGI
  are the model and agent-runtime layers that Protoweft plugs into.
- **Not a reimplementation of the protocol SDKs.** Where an official Java SDK exists (MCP, A2A),
  Protoweft wraps it rather than re-deriving the wire format. (UTCP, RAP and AG-UI have no official
  Java SDK, so their connectors are implemented directly from the specifications. AG-UI has a
  community Java SDK; it is not used, to keep third-party runtime dependencies out of users'
  applications. Its optional Protobuf binding uses protobuf-java only when the application adds it.)
- **Not a lowest-common-denominator façade.** The core does not force the protocols into a single
  unified model where their domains genuinely differ (e.g. A2A tasks vs. tool calls).
- **No guarantee of full feature coverage** for any protocol; coverage is declared per connector.

## Technology

| Aspect | Choice | Rationale |
|---|---|---|
| Language baseline | Java 17 (LTS) | The floor of every wrapped SDK (MCP, a2a-java, LangChain4j, the Anthropic and OpenAI SDKs) and of Spring Boot 4; widest enterprise reach. |
| Build | Gradle (Kotlin DSL), multi-module, with a BOM | Multi-module ergonomics; one BOM aligns versions for users. |
| JSON | No JSON library in the core; Jackson 3 where Protoweft needs JSON | Matches MCP SDK 2.x and Spring Boot 4; the core stays dependency-free. |
| Spring | Spring Boot 4.x only | 3.5 is out of OSS support, and 4.x defaults to Jackson 3. |
| Docs | Zensical (`mkdocs.yml`) on GitHub Pages, plus aggregated Javadoc | The maintained successor of Material for MkDocs. |
| License | MIT | Chosen by the project owner. |

See the architecture spec (`protoweft-architecture`) for the module layout, the per-protocol
compatibility matrix, and the cited sources.
