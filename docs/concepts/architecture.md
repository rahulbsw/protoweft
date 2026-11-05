# Architecture

AgentWeave is a small, protocol-neutral core with one module per protocol on top of it. Framework
integrations sit on top of those, and nothing depends on them.

## Modules

```mermaid
flowchart BT
    core["agentweave-core<br/><small>neutral API, no dependencies</small>"]
    server["agentweave-server<br/><small>servlet hosting contract</small>"]
    jetty["agentweave-server-jetty<br/><small>embedded Jetty 12</small>"]
    mcp["agentweave-mcp"]
    a2a["agentweave-a2a"]
    utcp["agentweave-utcp"]
    lc4j["agentweave-langchain4j"]
    starter["agentweave-spring-boot-starter"]

    mcpsdk[("MCP Java SDK 2.1.0")]
    a2asdk[("a2a-java 1.5.0.Final")]
    jackson[("Jackson 3")]
    lc4jlib[("LangChain4j 1.23.0")]
    boot[("Spring Boot 4")]

    server --> core
    jetty --> server
    mcp --> server
    a2a --> server
    utcp --> server
    lc4j --> core
    starter --> server
    starter -. optional .-> mcp
    starter -. optional .-> a2a
    starter -. optional .-> utcp

    mcp --> mcpsdk
    a2a --> a2asdk
    utcp --> jackson
    lc4j --> lc4jlib
    starter -. provided .-> boot
```

| Module | Responsibility | Depends on |
|---|---|---|
| `agentweave-core` | Tool, agent, auth, observation, error and compliance types. | Nothing |
| `agentweave-server` | `ServerEndpointProvider`, `ServletEndpoint` and `InboundAuthFilter`: how protocol servers hand HTTP endpoints to a host. | core; servlet API provided by the host |
| `agentweave-server-jetty` | `AgentWeaveServer`, an embedded Jetty 12 (Jakarta EE 11, Servlet 6.1) host. | server, Jetty |
| `agentweave-mcp` | `McpClientConnector`, `McpToolServer`. | server, MCP Java SDK |
| `agentweave-a2a` | `A2aAgentConnector`, `A2aAgentServer`. | server, a2a-java client, server-common and JSON-RPC transport |
| `agentweave-utcp` | `UtcpClientConnector`, `UtcpManualProvider`. | server, Jackson 3 (internal), `java.net.http` |
| `agentweave-langchain4j` | `AgentWeaveToolProvider`, `LangChain4jAgents`, `LangChain4jTools`. | core, LangChain4j |
| `agentweave-spring-boot-starter` | Auto-configuration, properties, Micrometer and Actuator. | server; protocol modules and Spring are `compileOnly` |
| `agentweave-bom` | Version alignment. | — |

## Dependency rules

- **The core depends on nothing.** It holds no protocol, JSON, servlet or telemetry types.
- **Each protocol module depends on the core, the hosting contract and its own SDK.** SDK types
  never appear in a public signature, and the SDK is an `implementation` dependency, so it doesn't
  leak onto your compile classpath. The servlet API is `compileOnly`: the host provides it.
- **Protocol modules don't depend on each other.** An A2A agent becomes a tool through the core's
  `ToolConnector` interface, not through a dependency on an MCP module.
- **Nothing depends on the framework integrations.** Plain Java and other agent frameworks use the
  core and a protocol module directly. The Spring Boot starter declares the protocol modules
  `compileOnly`; you add the ones you use.

## The core API

| Area | Types | Page |
|---|---|---|
| Tools | `ToolSource`, `ToolConnector`, `ToolSpec`, `ToolInvocation`, `ToolOutcome`, `Content`, `ToolRegistry`, `LocalTools`, `@AgentTool`, `@ToolParam`, `ToolHandler` | [Tools and the registry](tools.md) |
| Agents | `AgentConnector`, `AgentHandler`, `AgentTaskContext`, `AgentRequest`, `AgentMessage`, `AgentReply`, `AgentEvent`, `AgentArtifact`, `AgentDescriptor`, `TaskState` | [Agents](agents.md) |
| Security | `CredentialProvider`, `Credential`, `InboundAuthenticator`, `InboundRequest`, `AuthDecision`, `CallerIdentity` | [Security and credentials](security.md) |
| Errors and telemetry | `AgentWeaveException`, `ErrorCategory`, `ObservationHandler` | [Errors and observability](errors-and-observability.md) |
| Connectors | `ProtocolConnector`, `ProtocolId`, `ComplianceReport` | [Versions and compliance](compliance.md) |

Every connector is a `ProtocolConnector`: it is built by a protocol-specific builder, started
explicitly with `start()`, and closed with `close()` (safe to call more than once). Tool connectors
(MCP, UTCP, and A2A's `ask` tool) also implement `ToolConnector`; the A2A connector additionally
implements `AgentConnector`.

The core API is blocking. The MCP SDK is reactive internally; AgentWeave uses its synchronous
facade on the client and keeps Reactor off the public API.

## How servers are hosted

Protocol servers don't start an HTTP server themselves. Each one is a `ServerEndpointProvider`
that returns `ServletEndpoint`s: a name, the protocol, URL patterns, a servlet, and whether it
needs async support.

| Server | Endpoints (default paths) | Async |
|---|---|---|
| `McpToolServer.Http` | `/mcp` | yes |
| `A2aAgentServer` | `/.well-known/agent-card.json`, `/a2a` | yes |
| `UtcpManualProvider` | `/utcp`, `/utcp/*` | no |

A host mounts every endpoint and puts an `InboundAuthFilter` in front of it, so one
`InboundAuthenticator` protects all protocols. Two hosts are provided:

- [`AgentWeaveServer`](../integrations/jetty.md) (embedded Jetty) for plain Java.
- The [Spring Boot starter](../integrations/spring-boot.md), which registers the endpoints on
  Boot's servlet container.

Any other Jakarta Servlet 6 container works the same way: register each endpoint's servlet on its
URL patterns, add an `InboundAuthFilter` on the same patterns, and enable async support on both
when `asyncSupported()` is true.

## Design records

The design decisions behind each module, with their sources, are kept as specs in the
repository: `goal-and-requirements.md`, `architecture.md`, and a `SPEC.md` in each module
directory. When this site and a module's `SPEC.md` disagree, the `SPEC.md` and the code are
authoritative.
