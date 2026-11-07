# Reference

## API reference (Javadoc)

The [aggregated Javadoc](../api/index.html) covers every published module in one site. Start with
these entry points:

| Package | Start with |
|---|---|
| `io.github.rahulbsw.protoweft.core.tool` | `ToolSource`, `ToolConnector`, `ToolRegistry`, `LocalTools`, `AgentTool`, `ToolSubscription`, `ToolContext` |
| `io.github.rahulbsw.protoweft.core.agent` | `AgentConnector`, `AgentHandler`, `AgentTaskContext` |
| `io.github.rahulbsw.protoweft.core.server` | `InboundAuthenticator`, `CallerIdentity` |
| `io.github.rahulbsw.protoweft.core` | `ComplianceReport`, `ProtoweftException`, `ErrorCategory` |
| `io.github.rahulbsw.protoweft.mcp` | `McpClientConnector`, `McpToolServer`, `McpFeatures` |
| `io.github.rahulbsw.protoweft.a2a` | `A2aAgentConnector`, `A2aAgentServer`, `A2aFeatures` |
| `io.github.rahulbsw.protoweft.utcp` | `UtcpClientConnector`, `UtcpManualProvider`, `UtcpFeatures` |
| `io.github.rahulbsw.protoweft.rap` | `RapClientConnector`, `RapCallbackEndpoint`, `RapCallbackListener`, `RapToolServer`, `RapCallContext`, `RapStore`, `RapFeatures` |
| `io.github.rahulbsw.protoweft.core.auth` | `CredentialProvider`, `HttpAuthorizer` |
| `io.github.rahulbsw.protoweft.mcp` | `McpResources`, `McpPrompts`, `@McpResource`, `@McpPrompt` (resources and prompts) |
| `io.github.rahulbsw.protoweft.server.jetty` | `ProtoweftServer` |
| `io.github.rahulbsw.protoweft.oauth` | `OAuthClient`, `ClientRegistration`, `BearerTokenAuthenticator`, `JwtTokenVerifier`, `ProtectedResourceMetadataEndpoint` |
| `io.github.rahulbsw.protoweft.discovery` | `CapabilityCatalog`, `DiscoveryQuery`, `DiscoveryTools`, `SourcePolicy` |
| `io.github.rahulbsw.protoweft.discovery.embeddings`, `.anthropic`, `.openai` | `OfflineEmbedders`, `AnthropicDiscovery`, `OpenAiDiscovery` |
| `io.github.rahulbsw.protoweft.langchain4j` | `ProtoweftToolProvider`, `LangChain4jAgents`, `LangChain4jTools`, `CatalogToolSearchStrategy` |
| `io.github.rahulbsw.protoweft.springai` | `ProtoweftToolCallbacks`, `CatalogToolIndex`, `SpringAiAgents`, `SpringAiTools` |
| `io.github.rahulbsw.protoweft.argi` | `ArgiTools`, `CatalogToolSelectionInterceptor`, `ArgiAgents` |
| `io.github.rahulbsw.protoweft.spring.boot` | `ProtoweftConnectors`, `ProtoweftProperties`, `ProtoweftServerProperties`, `ProtoweftSpringAiProperties` |

## Coordinates

Group `io.github.rahulbsw`, version `0.1.0-SNAPSHOT`, not yet on Maven Central
([build from source](../getting-started.md#build-protoweft-from-source)).

| Artifact | |
|---|---|
| `protoweft-bom` | Bill of materials |
| `protoweft-core` | Protocol-neutral API |
| `protoweft-server` | Servlet hosting contract |
| `protoweft-server-jetty` | Embedded Jetty host |
| `protoweft-mcp` | MCP client and server |
| `protoweft-a2a` | A2A client and server |
| `protoweft-utcp` | UTCP client and provider |
| `protoweft-rap` | RAP client and tool server |
| `protoweft-oauth` | OAuth 2.1 client and resource server (MCP authorization) |
| `protoweft-discovery` | Tool discovery: capability catalog and search |
| `protoweft-discovery-embeddings` | The offline embedder for semantic discovery search (ONNX Runtime) |
| `protoweft-model-bge-small-en-v15` | The bge-small-en-v1.5 model files (data only, MIT) |
| `protoweft-discovery-anthropic` | Anthropic native tool-search adapter |
| `protoweft-discovery-openai` | OpenAI native tool-search adapter |
| `protoweft-langchain4j` | LangChain4j adapter |
| `protoweft-spring-ai` | Spring AI adapter |
| `protoweft-argi` | ARGI `2.1.0-RC1` adapter |
| `protoweft-spring-boot-starter` | Spring Boot 4 auto-configuration |

`protoweft-argi-neutral` isn't published, isn't in the BOM and isn't in the aggregated Javadoc; see
[ARGI](../integrations/argi.md#protoweft-argi-neutral-unpublished).

## More

- [Changelog](changelog.md)
- [License](license.md)
- [Versions and compliance](../concepts/compliance.md)
- [Spring Boot property reference](../integrations/spring-boot.md#property-reference)
