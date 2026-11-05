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
| `io.agentweave.core.auth` | `CredentialProvider`, `HttpAuthorizer` |
| `io.agentweave.mcp` | `McpResources`, `McpPrompts`, `@McpResource`, `@McpPrompt` (resources and prompts) |
| `io.agentweave.server.jetty` | `AgentWeaveServer` |
| `io.agentweave.oauth` | `OAuthClient`, `ClientRegistration`, `BearerTokenAuthenticator`, `JwtTokenVerifier`, `ProtectedResourceMetadataEndpoint` |
| `io.agentweave.discovery` | `CapabilityCatalog`, `DiscoveryQuery`, `DiscoveryTools`, `SourcePolicy` |
| `io.agentweave.discovery.embeddings`, `.anthropic`, `.openai` | `OfflineEmbedders`, `AnthropicDiscovery`, `OpenAiDiscovery` |
| `io.agentweave.langchain4j` | `AgentWeaveToolProvider`, `LangChain4jAgents`, `LangChain4jTools`, `CatalogToolSearchStrategy` |
| `io.agentweave.springai` | `AgentWeaveToolCallbacks`, `CatalogToolIndex`, `SpringAiAgents`, `SpringAiTools` |
| `io.agentweave.argi` | `ArgiTools`, `CatalogToolSelectionInterceptor`, `ArgiAgents` |
| `io.agentweave.spring.boot` | `AgentWeaveConnectors`, `AgentWeaveProperties`, `AgentWeaveServerProperties`, `AgentWeaveSpringAiProperties` |

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
| `agentweave-oauth` | OAuth 2.1 client and resource server (MCP authorization) |
| `agentweave-discovery` | Tool discovery: capability catalog and search |
| `agentweave-discovery-embeddings` | Embedders for semantic discovery search |
| `agentweave-discovery-anthropic` | Anthropic native tool-search adapter |
| `agentweave-discovery-openai` | OpenAI native tool-search adapter |
| `agentweave-langchain4j` | LangChain4j adapter |
| `agentweave-spring-ai` | Spring AI adapter |
| `agentweave-argi` | ARGI `2.1.0-RC1` adapter |
| `agentweave-spring-boot-starter` | Spring Boot 4 auto-configuration |

`agentweave-argi-neutral` isn't published, isn't in the BOM and isn't in the aggregated Javadoc; see
[ARGI](../integrations/argi.md#agentweave-argi-neutral-unpublished).

## More

- [Changelog](changelog.md)
- [License](license.md)
- [Versions and compliance](../concepts/compliance.md)
- [Spring Boot property reference](../integrations/spring-boot.md#property-reference)
