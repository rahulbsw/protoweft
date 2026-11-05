# AgentWeave

**The protocol backbone for Java agents.** AgentWeave connects Java applications to the
emerging agent and tool interoperability protocols —
[Model Context Protocol (MCP)](https://modelcontextprotocol.io),
[Agent2Agent (A2A)](https://a2a-protocol.org), and the
[Universal Tool Calling Protocol (UTCP)](https://www.utcp.io) — behind one protocol-neutral API.

Write a tool once and serve it over MCP and UTCP. Consume MCP servers, UTCP manuals and remote
A2A agents through a single tool registry. Run your application as an A2A agent. Plug all of it
into [LangChain4j](https://docs.langchain4j.dev), Spring Boot, or plain Java.

```java
// Define a tool once...
class Weather {
    @AgentTool(description = "Current temperature in a city, in Celsius")
    public double temperature(@ToolParam(description = "City name") String city) {
        return 21.5;
    }
}
LocalTools tools = LocalTools.builder().scan(new Weather()).build();

// ...and use tools from anywhere through one registry.
try (ToolRegistry registry = new ToolRegistry()
        .register("local", tools)
        .register("files", connectedMcpServer)) {
    ToolOutcome out = registry.invoke(ToolInvocation.of("local.temperature", Map.of("city", "Oslo")));
}
```

## Why AgentWeave

<div class="grid cards" markdown>

- **One API, three protocols.** Application code depends on `ToolSource`, `ToolConnector` and
  `AgentConnector`, not on a protocol SDK. Changing the protocol means changing one builder call.
- **Both directions.** Every protocol has a client and a server: consume tools and agents, or
  publish your own.
- **Honest compatibility.** Every connector publishes a `ComplianceReport` that lists the SDK
  version, the wire revisions it speaks, the revision it negotiated, and exactly which features
  are and aren't implemented.
- **Framework-neutral.** The core has no dependencies. Spring Boot and LangChain4j support are
  optional modules that sit on top of it.

</div>

## What AgentWeave is not

AgentWeave doesn't provide LLM clients, prompt chains, memory or RAG. Use LangChain4j or Spring
AI for those; AgentWeave gives them a protocol layer. Where an official Java SDK exists (MCP,
A2A), AgentWeave wraps it rather than re-implementing the wire format.

## Next steps

- [Getting started](getting-started.md): add AgentWeave and make your first calls.
- [Architecture](concepts/architecture.md): the modules and how they fit together.
- [Versions and compliance](concepts/compliance.md): which protocol versions are supported and
  how that's reported.
