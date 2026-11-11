# Protoweft

**The protocol backbone for Java agents.** Protoweft connects Java applications to the agent
interoperability protocols —
[Model Context Protocol (MCP)](https://modelcontextprotocol.io),
[Agent2Agent (A2A)](https://a2a-protocol.org), the
[Universal Tool Calling Protocol (UTCP)](https://www.utcp.io), the Hydro project's
[Reactive Agent Protocol (RAP)](protocols/rap.md) and the
[Agent–User Interaction protocol (AG-UI)](protocols/agui.md) — behind one protocol-neutral Java 17
API, in both the client and the server role.

Write a tool once and serve it over MCP, UTCP and RAP. Consume MCP servers, UTCP manuals, RAP tool
servers and remote A2A and AG-UI agents through a single tool registry. Publish your own agent over
A2A, or to web frontends over AG-UI. Use all of it from plain
Java, Spring Boot 4, or the [LangChain4j](integrations/langchain4j.md),
[Spring AI](integrations/spring-ai.md) and [ARGI](integrations/argi.md) agent frameworks.

```java
public class Weather {
    @AgentTool(description = "Current temperature in a city, in Celsius")
    public double temperature(@ToolParam(description = "City name") String city) { return 21.5; }
}

LocalTools tools = LocalTools.builder().scan(new Weather()).build();

// Serve the tool over MCP and UTCP from one embedded server...
ProtoweftServer server = ProtoweftServer.builder().port(8080)
        .endpoints(McpToolServer.http(tools), UtcpManualProvider.builder(tools).build())
        .start();

// ...and call any MCP server through the same protocol-neutral API.
ToolConnector mcp = McpClientConnector.http("http://localhost:8080").build();
mcp.start();
ToolOutcome out = mcp.invoke(ToolInvocation.of("temperature", Map.of("city", "Oslo")));
System.out.println(out.text());   // 21.5
```

## Why Protoweft

<div class="grid cards" markdown>

- **One API, five protocols**

    ---

    Application code depends on `ToolSource`, `ToolConnector` and `AgentConnector`, not on a
    protocol SDK. Switching protocols means changing the one builder call that names it.

- **Client and server for every protocol**

    ---

    Consume remote tools and agents, or publish your own. Every server endpoint runs behind the same
    `InboundAuthenticator`.

- **Honest compatibility**

    ---

    Every connector publishes a `ComplianceReport`: SDK version, the wire revisions it speaks, the
    revision it negotiated, and exactly which features are and aren't implemented.

- **Framework-neutral**

    ---

    The core has no dependencies. Spring Boot, LangChain4j, Spring AI and ARGI support are optional
    modules on top; the core and protocol modules don't depend on them.

- **Wraps the official SDKs**

    ---

    MCP and A2A are built on their official Java SDKs. UTCP and RAP, which have no Java SDK, are
    implemented from their specifications and checked against the reference implementations.

- **One registry for everything**

    ---

    `ToolRegistry` namespaces every source as `<source>.<tool>`: local methods, MCP servers, UTCP
    manuals, RAP tool servers, and A2A agents (as an `ask` tool).

</div>

## Protocol support at a glance

| Protocol | Client | Server | Transport / binding | Specification | Built on |
|---|---|---|---|---|---|
| MCP | `McpClientConnector` | `McpToolServer` | stdio, Streamable HTTP | `2024-11-05` to `2025-11-25` | MCP Java SDK `2.1.0` |
| A2A | `A2aAgentConnector` | `A2aAgentServer` | JSON-RPC over HTTP, SSE streaming | `1.0` | a2a-java `1.5.0.Final` |
| UTCP | `UtcpClientConnector` | `UtcpManualProvider` | HTTP, text and file manuals; `http` and `text` tools | `1.1` (accepts `1.0` manuals) | Implemented from the spec |
| RAP | `RapClientConnector` | `RapToolServer` | HTTP + JSON with callbacks; subscriptions, OAuth and choice prompts, cancellation, thread closure | unversioned; Hydro Infinity `e1a3e4b` | Implemented from the spec |

Each protocol guide lists exactly what is and isn't covered: [MCP](protocols/mcp.md),
[A2A](protocols/a2a.md), [UTCP](protocols/utcp.md), [RAP](protocols/rap.md). The full version matrix is in
[Versions and compliance](concepts/compliance.md).

## What Protoweft is not

- **Not an LLM framework.** Protoweft has no model clients, prompt templates, chains, memory or
  RAG. Use [LangChain4j](https://docs.langchain4j.dev) or [Spring AI](https://spring.io/projects/spring-ai)
  for those; Protoweft gives them a protocol layer.
- **Not an agent runtime or orchestrator.** It doesn't plan, schedule or persist work. A2A task
  state is kept in memory.
- **Not a re-implementation of the protocol SDKs.** Where an official Java SDK exists, Protoweft
  wraps it.
- **Not a lowest-common-denominator façade.** Tools (MCP, UTCP, RAP) and agents (A2A) stay
  separate concepts in the core API.

!!! note "Pre-release"
    Protoweft is at `0.1.0-SNAPSHOT` and isn't published to Maven Central yet.
    [Getting started](getting-started.md) shows how to build it from source.

## Next steps

- [Getting started](getting-started.md): add Protoweft and make your first calls.
- [Architecture](concepts/architecture.md): the modules and how they fit together.
- [Examples](examples.md): runnable applications and starter templates.
- [API reference](api/index.html): the aggregated Javadoc.
