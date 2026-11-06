# Protoweft

**The protocol backbone for Java agents.** Protoweft puts the agent and tool interoperability
protocols — [MCP](https://modelcontextprotocol.io), [A2A](https://a2a-protocol.org) and
[UTCP](https://www.utcp.io) — behind one protocol-neutral Java 17 API, in both the client and the
server role, with optional LangChain4j, Spring AI, ARGI and Spring Boot 4 integrations.

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
- **Tool discovery at scale.** A provider-neutral capability catalog keeps short cards apart from
  full schemas. It searches with hybrid keyword and semantic ranking and applies exact filters
  (protocol, version, permissions, availability). A low-confidence fallback broadens the search.
  Models use it through generic meta-tools or through Anthropic's and OpenAI's native tool search.
  Effects are [measured](docs/discovery/evaluation.md), not assumed.
- **Conformance-tested.** The official MCP conformance suite and the A2A TCK run in CI; every
  expected failure is [listed with its reason](docs/operations/conformance.md).
- **Framework-neutral.** The core has no dependencies. LangChain4j, Spring AI, ARGI and Spring Boot
  support are optional modules on top.

## Protocol support

| Protocol | Client | Server | Transport / binding | Specification | Built on |
|---|---|---|---|---|---|
| MCP | `McpClientConnector` | `McpToolServer` | stdio, Streamable HTTP; tools, resources, prompts, completion, logging, progress, OAuth | `2024-11-05` to `2025-11-25` | MCP Java SDK `2.1.0` |
| A2A | `A2aAgentConnector` | `A2aAgentServer` | JSON-RPC, SSE streaming | `1.0` | a2a-java `1.5.0.Final` |
| UTCP | `UtcpClientConnector` | `UtcpManualProvider` | HTTP, text and file manuals; `http` and `text` tools | `1.1` (accepts `1.0`) | implemented from the spec |

Coverage and limitations are documented per protocol in the [user guide](docs/index.md).

Protoweft is not an LLM framework: it has no model clients, chains, memory or RAG. Use it with
[LangChain4j](https://docs.langchain4j.dev), [Spring AI](https://spring.io/projects/spring-ai) or
[ARGI](https://github.com/agentic-ai-java/argi).

## Modules

| Artifact | What it is |
|---|---|
| `protoweft-core` | Protocol-neutral API. No dependencies. |
| `protoweft-mcp` | MCP client and tool server (stdio, Streamable HTTP). |
| `protoweft-a2a` | A2A client and agent server (JSON-RPC). |
| `protoweft-utcp` | UTCP client and manual provider. |
| `protoweft-server`, `protoweft-server-jetty` | Servlet hosting contract and an embedded Jetty 12 host. |
| `protoweft-oauth` | OAuth 2.1 for MCP authorization: client and resource-server pieces (Nimbus). |
| `protoweft-discovery` | Capability catalog, hybrid search, fallback, sessions, meta-tools, provider payloads. |
| `protoweft-discovery-embeddings` | Offline bge-small embedder on ONNX Runtime alone (model in `protoweft-model-bge-small-en-v15`). |
| `protoweft-discovery-anthropic` / `-openai` | Typed native tool-search adapters on the official SDKs. |
| `protoweft-langchain4j` | LangChain4j adapter: tools in, agents and `@Tool`s out, and catalog tool search. |
| `protoweft-spring-ai` | Spring AI adapter: tools as `ToolCallback`s, catalog tool search, `ChatClient` as an A2A agent. |
| `protoweft-argi` | ARGI adapter: tools in, catalog tool selection, agents with human-in-the-loop over A2A. |
| `protoweft-spring-boot-starter` | Spring Boot 4 auto-configuration with opt-in servers, Spring AI wiring, Micrometer and Actuator. |
| `protoweft-bom` | Version alignment. |

## Getting started

Requirements: Java 17 or newer. Protoweft `0.1.0-SNAPSHOT` isn't on Maven Central yet; build it
and install it into your local Maven repository:

```bash
./gradlew build                 # compile and test everything
./gradlew publishToMavenLocal   # io.github.rahulbsw:*:0.1.0-SNAPSHOT into ~/.m2
```

Then, in your project:

```kotlin
repositories { mavenLocal(); mavenCentral() }

dependencies {
    implementation(platform("io.github.rahulbsw:protoweft-bom:0.1.0-SNAPSHOT"))
    implementation("io.github.rahulbsw:protoweft-mcp")
    implementation("io.github.rahulbsw:protoweft-server-jetty")
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
  [LangChain4j](docs/integrations/langchain4j.md), [Spring AI](docs/integrations/spring-ai.md),
  [ARGI](docs/integrations/argi.md), [OAuth](docs/integrations/oauth.md),
  [embedded Jetty](docs/integrations/jetty.md)
- [Quickstart](docs/quickstart.md), [examples](docs/examples.md),
  [conformance](docs/operations/conformance.md), [troubleshooting](docs/operations/troubleshooting.md)

The documentation site is built with [Zensical](https://zensical.org) (`zensical build`), and the
API reference with `./gradlew aggregateJavadoc`.

Design decisions and their sources are recorded as specs: [`goal-and-requirements.md`](goal-and-requirements.md),
[`architecture.md`](architecture.md), and a `SPEC.md` in each module.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE)
