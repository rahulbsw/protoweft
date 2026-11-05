# Changelog

All notable changes to AgentWeave are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Core (`agentweave-core`).**
  - Tools: `ToolSource`, `ToolConnector`, a namespaced `ToolRegistry`, and `LocalTools`
    (`@AgentTool`/`@ToolParam`, with JSON Schema generated from Java types).
  - `ToolSpec` metadata carries behavior hints, title and tags. `Content` has `Text`, `Data`,
    `Json`, `Link` and `Unmapped` parts.
  - Agents: `AgentHandler`/`AgentTaskContext` for the full task lifecycle, and `AgentConnector`.
  - Cross-cutting: `ComplianceReport`, `CredentialProvider`, `InboundAuthenticator` with
    `CallerIdentity`, `ObservationHandler`, and `AgentWeaveException` with `ErrorCategory`.
- **Hosting.** `agentweave-server` (servlet endpoints plus an inbound-auth filter) and
  `agentweave-server-jetty` (an embedded Jetty 12 host).
- **MCP (`agentweave-mcp`, MCP Java SDK 2.1.0).** A client over stdio and Streamable HTTP. A tool
  server (`McpToolServer`) over stdio and Streamable HTTP, with Origin validation and MCP
  tool-annotation mapping.
- **A2A (`agentweave-a2a`, a2a-java 1.5.0.Final, A2A 1.0, JSON-RPC).**
  - Client: `A2aAgentConnector`, also usable as a single `ask` tool.
  - Agent server: `A2aAgentServer`, covering the full lifecycle (streaming, input-required,
    cancel, resubscribe, push notifications, extended card) with per-caller task ownership.
- **UTCP (`agentweave-utcp`, spec 1.1, implemented from the spec).**
  - Client: http, text and file manuals; http tools; api_key, basic and oauth2 auth; namespaced
    variables; tag search; https/loopback security rules.
  - Manual provider: `UtcpManualProvider` publishes UTCP manuals for local tools.
- **Tool discovery.**
  - `agentweave-discovery`: a capability catalog with hybrid BM25 + vector search (RRF), exact
    filters (protocol, version, permissions, availability), a progressive low-confidence
    fallback, sessions, generic `search`/`describe`/`call` meta-tools, and raw-JSON Anthropic and
    OpenAI tool-search payloads.
  - `agentweave-discovery-embeddings`: an offline bge-small and MiniLM embedder, plus an adapter
    for any LangChain4j `EmbeddingModel`. Hybrid search uses calibrated confidence presets for
    both.
  - `agentweave-discovery-anthropic` and `agentweave-discovery-openai`: typed tool-search adapters
    on the official SDKs.
- **LangChain4j (`agentweave-langchain4j`).** AgentWeave tools in AI services, AI services as A2A
  agents, `@Tool` methods served over MCP and UTCP, and a catalog-backed `ToolSearchStrategy`.
- **Spring Boot 4 starter (`agentweave-spring-boot-starter`).** Connectors from `application.yml`,
  `@AgentTool` bean discovery, and opt-in MCP, UTCP and A2A endpoints. Also: Spring Security
  integration, Micrometer and Actuator support, and opt-in tool discovery with MCP meta-tools.
- **Evaluation harness (`evals/tool-discovery`).**
  - Data: ToolRet and MCPToolBench++ loaders.
  - Metrics: retrieval (Recall, nDCG, MRR, Completeness) and model-in-the-loop (selection
    accuracy, wrong-tool rate, call correctness, discovery calls, tokens, cost).
  - Runs: four arms (all-tools, top-k, generic search, native adapters), confidence calibration,
    and a cost guard.
- **Examples.** An MCP client, a plain-Java gateway (one tool over three protocols), a Spring Boot
  app, and a LangChain4j agent served over A2A.
- **Project.** `agentweave-bom`, the MIT license, CI on JDK 17 and 21, aggregated Javadoc, a
  documentation site (Zensical, GitHub Pages), and contribution and security guides.

### Fixed
- Stopping an MCP HTTP server now releases MCP Java SDK 2.1.0's non-daemon session-sweeper thread,
  so the JVM can exit (works around an SDK issue; see `agentweave-mcp/SPEC.md`).
