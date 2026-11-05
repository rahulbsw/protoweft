# Reference

## API reference (Javadoc)

The [aggregated Javadoc](../api/index.html) covers every published module in one site. Start with
these entry points:

| Package | Start with |
|---|---|
| `io.agentweave.core.tool` | `ToolSource`, `ToolConnector`, `ToolRegistry`, `LocalTools`, `AgentTool` |
| `io.agentweave.core.agent` | `AgentConnector`, `AgentHandler`, `AgentTaskContext` |
| `io.agentweave.core.server` | `InboundAuthenticator`, `CallerIdentity` |
| `io.agentweave.core` | `ComplianceReport`, `AgentWeaveException`, `ErrorCategory` |
| `io.agentweave.mcp` | `McpClientConnector`, `McpToolServer`, `McpFeatures` |
| `io.agentweave.a2a` | `A2aAgentConnector`, `A2aAgentServer`, `A2aFeatures` |
| `io.agentweave.utcp` | `UtcpClientConnector`, `UtcpManualProvider`, `UtcpFeatures` |
| `io.agentweave.server.jetty` | `AgentWeaveServer` |
| `io.agentweave.langchain4j` | `AgentWeaveToolProvider`, `LangChain4jAgents`, `LangChain4jTools` |
| `io.agentweave.spring.boot` | `AgentWeaveConnectors`, `AgentWeaveProperties`, `AgentWeaveServerProperties` |

## Coordinates

Group `io.agentweave`, version `0.1.0-SNAPSHOT`, not yet on Maven Central
([build from source](../getting-started.md#build-agentweave-from-source)).

| Artifact | |
|---|---|
| `agentweave-bom` | Bill of materials |
| `agentweave-core` | Protocol-neutral API |
| `agentweave-server` | Servlet hosting contract |
| `agentweave-server-jetty` | Embedded Jetty host |
| `agentweave-mcp` | MCP client and server |
| `agentweave-a2a` | A2A client and server |
| `agentweave-utcp` | UTCP client and provider |
| `agentweave-langchain4j` | LangChain4j adapter |
| `agentweave-spring-boot-starter` | Spring Boot 4 auto-configuration |

## More

- [Changelog](changelog.md)
- [License](license.md)
- [Versions and compliance](../concepts/compliance.md)
- [Spring Boot property reference](../integrations/spring-boot.md#property-reference)
