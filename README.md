# AgentWeave

**The protocol backbone for Java agents.** AgentWeave puts the agent and tool interoperability
protocols — [MCP](https://modelcontextprotocol.io), [A2A](https://a2a-protocol.org) and
[UTCP](https://www.utcp.io) — behind one protocol-neutral Java 17 API, in both the client and the
server role, with optional LangChain4j and Spring Boot 4 integrations.

```java
public class Weather {
    @AgentTool(description = "Current temperature in a city, in Celsius")
    public double temperature(@ToolParam(description = "City name") String city) { return 21.5; }
}

LocalTools tools = LocalTools.builder().scan(new Weather()).build();

// Serve the tool over MCP and UTCP from one embedded server...
AgentWeaveServer server = AgentWeaveServer.builder().port(8080)
        .endpoints(McpToolServer.http(tools), UtcpManualProvider.builder(tools).build())
        .start();

// ...and call any MCP server through the same protocol-neutral API.
ToolConnector mcp = McpClientConnector.http("http://localhost:8080").build();
mcp.start();
System.out.println(mcp.invoke(ToolInvocation.of("temperature", Map.of("city", "Oslo"))).text());   // 21.5
```

## Features

- **One API, three protocols.** Application code depends on `ToolSource`, `ToolConnector` and
  `AgentConnector`; only the builder call names a protocol.
- **Client and server for every protocol.** Consume MCP servers, UTCP manuals and A2A agents;
  publish tools over MCP and UTCP and agents over A2A, all behind one `InboundAuthenticator`.
- **One tool registry.** `ToolRegistry` namespaces local methods, MCP servers, UTCP manuals and A2A
  agents (as an `ask` tool) as `<source>.<tool>`.
- **Honest compatibility.** Every connector publishes a `ComplianceReport` with the SDK version, the
  wire revisions it speaks, the negotiated revision, and the supported and unsupported features.
- **Framework-neutral.** The core has no dependencies. LangChain4j and Spring Boot support are
  optional modules on top.

## Protocol support

| Protocol | Client | Server | Transport / binding | Specification | Built on |
|---|---|---|---|---|---|
| MCP | `McpClientConnector` | `McpToolServer` | stdio, Streamable HTTP | `2024-11-05` to `2025-11-25` | MCP Java SDK `2.1.0` |
| A2A | `A2aAgentConnector` | `A2aAgentServer` | JSON-RPC, SSE streaming | `1.0` | a2a-java `1.5.0.Final` |
| UTCP | `UtcpClientConnector` | `UtcpManualProvider` | HTTP, text and file manuals; `http` and `text` tools | `1.1` (accepts `1.0`) | implemented from the spec |

Coverage and limitations are documented per protocol in the [user guide](docs/index.md).

AgentWeave is not an LLM framework: it has no model clients, chains, memory or RAG. Use it with
[LangChain4j](https://docs.langchain4j.dev) or [Spring AI](https://spring.io/projects/spring-ai).

## Modules

| Artifact | What it is |
|---|---|
| `agentweave-core` | Protocol-neutral API. No dependencies. |
| `agentweave-mcp` | MCP client and tool server (stdio, Streamable HTTP). |
| `agentweave-a2a` | A2A client and agent server (JSON-RPC). |
| `agentweave-utcp` | UTCP client and manual provider. |
| `agentweave-server`, `agentweave-server-jetty` | Servlet hosting contract and an embedded Jetty 12 host. |
| `agentweave-langchain4j` | LangChain4j adapter: tools in, agents and `@Tool`s out. |
| `agentweave-spring-boot-starter` | Spring Boot 4 auto-configuration with opt-in servers, Micrometer and Actuator. |
| `agentweave-bom` | Version alignment. |

## Getting started

Requirements: Java 17 or newer. AgentWeave `0.1.0-SNAPSHOT` isn't on Maven Central yet; build it
and install it into your local Maven repository:

```bash
./gradlew build                 # compile and test everything
./gradlew publishToMavenLocal   # io.agentweave:*:0.1.0-SNAPSHOT into ~/.m2
```

Then, in your project:

```kotlin
repositories { mavenLocal(); mavenCentral() }

dependencies {
    implementation(platform("io.agentweave:agentweave-bom:0.1.0-SNAPSHOT"))
    implementation("io.agentweave:agentweave-mcp")
    implementation("io.agentweave:agentweave-server-jetty")
}
```

Run an example:

```bash
./gradlew :examples:mcp-client-example:run          # MCP client against a bundled stdio server
./gradlew :examples:plain-java-gateway:run          # one tool and agent over MCP, UTCP and A2A
./gradlew :examples:spring-boot-example:bootRun     # the same in Spring Boot
```

## Documentation

- [Getting started](docs/getting-started.md)
- Concepts: [architecture](docs/concepts/architecture.md), [tools](docs/concepts/tools.md),
  [agents](docs/concepts/agents.md), [security](docs/concepts/security.md),
  [errors and observability](docs/concepts/errors-and-observability.md),
  [versions and compliance](docs/concepts/compliance.md)
- Protocols: [MCP](docs/protocols/mcp.md), [A2A](docs/protocols/a2a.md), [UTCP](docs/protocols/utcp.md)
- Integrations: [Spring Boot](docs/integrations/spring-boot.md),
  [LangChain4j](docs/integrations/langchain4j.md), [embedded Jetty](docs/integrations/jetty.md)
- [Examples](docs/examples.md), [troubleshooting](docs/operations/troubleshooting.md)

The documentation site is built with [Zensical](https://zensical.org) (`zensical build`), and the
API reference with `./gradlew aggregateJavadoc`.

Design decisions and their sources are recorded as specs: [`goal-and-requirements.md`](goal-and-requirements.md),
[`architecture.md`](architecture.md), and a `SPEC.md` in each module.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE)
