---
id: agentweave-goal
type: goal-and-requirements
status: draft
title: AgentWeave — interoperable agent & tool protocols for Java
---

# AgentWeave

*A small Java framework that makes it easy to add interoperable agent and tool protocols — MCP, A2A, UTCP — to any Java application.*

## Goal

Let Java applications speak the emerging agent/tool interoperability protocols through one
protocol-neutral programming model, instead of coupling to any single protocol's SDK and wire
format. A plain Java app, a bespoke agent framework, or a Spring Boot service should all be able to
discover and call tools (and, later, talk agent-to-agent) over whichever protocol the other side
speaks, while the application code stays written against stable, protocol-neutral AgentWeave
interfaces.

## Problem

Three overlapping standards are converging on how applications expose and consume AI tools and
agents:

- **Model Context Protocol (MCP)** — tool/resource/prompt access over JSON-RPC (stdio, SSE,
  Streamable HTTP).
- **Agent2Agent (A2A)** — agent-to-agent task/message exchange over JSON-RPC, gRPC, or REST.
- **Universal Tool Calling Protocol (UTCP)** — direct, "no wrapper server" tool calls described by a
  published manual.

Today a Java team wanting more than one of these must pull in each protocol's SDK directly (and for
UTCP, there is no official Java SDK at all), learn three different domain models, and scatter
protocol-specific types, auth, and error handling through their application. Version confusion makes
it worse: MCP alone carries two independent version axes — the date-based wire revision and the Java
SDK major version — that are routinely conflated. There is no neutral Java seam that lets an
application target "a tool" or "an agent" and swap the protocol underneath.

## Target users

- **Java application developers** adding tool/agent connectivity to an existing service or CLI.
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
6. **Honest compliance.** AgentWeave never claims support for a protocol feature it has not
   implemented; unsupported features are declared, not silently ignored.
7. **No invented protocol behavior.** Behavior follows the official specifications and reference
   implementations; any deviation is recorded in the owning spec.
8. **SDK version ≠ wire version.** Where a protocol has independent SDK and wire-format version axes
   (notably MCP), AgentWeave models them as separate, explicit fields and never collapses them.
9. **Protocol-specific types stay out of the shared core API** unless genuinely needed by more than
   one integration.

## Capabilities

What AgentWeave provides:

- A **protocol-neutral core**: value types for tools, invocations, and outcomes; a `ToolConnector`
  interface and a `ProtocolConnector` SPI; a neutral error model; and extension points for
  authentication/credentials, configuration, and observability.
- **Pluggable protocol connectors** — MCP, A2A, and UTCP — each a separate module implementing the
  core SPIs behind stable Java interfaces.
- An **optional Spring Boot starter** that auto-configures the core and selected connectors.
- **Explicit, testable compatibility reporting** per connector (protocol + SDK version + wire
  revisions + supported/unsupported features).
- **A runnable example** per integration demonstrating the extension model end to end.

### Smallest useful first build

The protocol-neutral **core** plus **one end-to-end MCP client connector** (stdio transport, wrapping
MCP Java SDK 2.x targeting wire revision 2025-11-25), exposing `ToolConnector`, publishing
a compliance report, with focused tests and a runnable example. It must be straightforward to add the
A2A and UTCP connectors and the Spring starter against the same core, without reworking it.

## Non-goals

- **Not an agent runtime or orchestration engine.** AgentWeave is the protocol seam, not a planner,
  memory store, or LLM client.
- **Not a reimplementation of the protocol SDKs.** Where an official Java SDK exists (MCP, A2A),
  AgentWeave wraps it rather than re-deriving the wire format. (UTCP has no official Java SDK, so its
  connector is implemented directly from the specification.)
- **Not a lowest-common-denominator façade.** The core does not force MCP, A2A, and UTCP into a single
  unified model where their domains genuinely differ (e.g. A2A tasks vs. tool calls).
- **No guarantee of full feature coverage** for any protocol; coverage is declared per connector.

## Technology

| Aspect | Choice | Rationale |
|---|---|---|
| Language baseline | Java 17 (LTS) | Required floor of both wrapped SDKs (MCP java-sdk, a2a-java) and of Spring Boot 3.x; widest enterprise reach. |
| Build | Gradle (Kotlin DSL), multi-module | Chosen for multi-module ergonomics, version catalogs, and convention plugins. |
| JSON | Jackson behind a core abstraction | Mirrors the MCP SDK's own pluggable-Jackson approach; keeps a JSON library out of the core API. |

See the architecture spec (`agentweave-architecture`) for the module layout, the per-protocol
compatibility matrix, and the cited sources.
