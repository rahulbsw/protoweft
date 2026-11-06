# Changelog

All notable changes to Protoweft are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Core (`protoweft-core`).**
  - Tools: `ToolSource`, `ToolConnector`, a namespaced `ToolRegistry`, and `LocalTools`
    (`@AgentTool`/`@ToolParam`, with JSON Schema generated from Java types).
  - `ToolSpec` metadata carries behavior hints, title and tags. `Content` has `Text`, `Data`
    (with an optional file name), `Json`, `Link`, `Resource` and `Unmapped` parts.
  - `ToolProgress` and `ProgressAwareToolHandler` for progress and log messages from running
    tools.
  - Agents: `AgentHandler`/`AgentTaskContext` for the full task lifecycle, and `AgentConnector`.
  - Cross-cutting: `ComplianceReport`, `CredentialProvider`, `HttpAuthorizer`,
    `InboundAuthenticator` with `CallerIdentity` and `WWW-Authenticate` challenges,
    `ObservationHandler`, and `ProtoweftException` with `ErrorCategory`.
- **Hosting.** `protoweft-server` (servlet endpoints plus an inbound-auth filter) and
  `protoweft-server-jetty` (an embedded Jetty 12 host).
- **MCP (`protoweft-mcp`, MCP Java SDK 2.1.0).** A client over stdio and Streamable HTTP. A tool
  server (`McpToolServer`) over stdio and Streamable HTTP, with Origin validation and MCP
  tool-annotation mapping. Both roles: resources (templates, subscriptions), prompts, completion,
  logging and progress, authored with `McpResources`/`McpPrompts` builders or `@McpResource`,
  `@McpResourceTemplate`, `@McpPrompt` and `@McpComplete`. The client takes an `HttpAuthorizer`
  for MCP authorization.
- **OAuth (`protoweft-oauth`, Nimbus).** `OAuthClient` (MCP 2025-11-25 authorization: discovery
  from a 401, PKCE, resource indicators, client credentials and interactive authorization code,
  pre-registration, client ID metadata documents and dynamic registration, refresh and step-up)
  and resource-server pieces: protected-resource metadata endpoint, `BearerTokenAuthenticator`,
  `JwtTokenVerifier`.
- **A2A (`protoweft-a2a`, a2a-java 1.5.0.Final, A2A 1.0, JSON-RPC).**
  - Client: `A2aAgentConnector`, also usable as a single `ask` tool.
  - Agent server: `A2aAgentServer`, covering the full lifecycle (streaming, input-required,
    cancel, resubscribe, push notifications, extended card) with per-caller task ownership.
- **UTCP (`protoweft-utcp`, spec 1.1, implemented from the spec).**
  - Client: http, text and file manuals; http tools; api_key, basic and oauth2 auth; namespaced
    variables; tag search; https/loopback security rules.
  - Manual provider: `UtcpManualProvider` publishes UTCP manuals for local tools.
- **Tool discovery.**
  - `protoweft-discovery`: a capability catalog with hybrid BM25 + vector search (RRF), exact
    filters (protocol, version, permissions, availability), a progressive low-confidence
    fallback, sessions, generic `search`/`describe`/`call` meta-tools, and raw-JSON Anthropic and
    OpenAI tool-search payloads.
  - `protoweft-discovery-embeddings`: an offline bge-small embedder on ONNX Runtime alone, with a
    pure-Java WordPiece tokenizer; the model ships as `protoweft-model-bge-small-en-v15`. Hybrid
    search uses a calibrated confidence preset for it, and convex score fusion chosen by
    measurement. Any LangChain4j `EmbeddingModel` plugs in through `LangChain4jEmbedder` in
    `protoweft-langchain4j`.
  - `protoweft-discovery-anthropic` and `protoweft-discovery-openai`: typed tool-search adapters
    on the official SDKs.
- **LangChain4j (`protoweft-langchain4j`).** Protoweft tools in AI services, AI services as A2A
  agents, `@Tool` methods served over MCP and UTCP, and a catalog-backed `ToolSearchStrategy`.
- **Spring AI (`protoweft-spring-ai`, Spring AI 2.0.2).** Protoweft tools as `ToolCallback`s,
  the catalog as a tool-search `ToolIndex`, a `ChatClient` served as a streaming A2A agent, and
  Spring AI tools served over MCP and UTCP.
- **ARGI (`protoweft-argi`, ARGI 2.1.0-RC1).** Protoweft tools in ARGI agents, catalog-based tool
  selection, and ARGI agents served over A2A with human-in-the-loop approvals as
  `input-required`. An unpublished `protoweft-argi-neutral` targets ARGI's Spring-free API at a
  pinned commit (`-PargiSnapshot`).
- **Spring Boot 4 starter (`protoweft-spring-boot-starter`).** Connectors from `application.yml`,
  `@AgentTool` bean discovery, and opt-in MCP, UTCP and A2A endpoints. Also: Spring Security
  integration, Micrometer and Actuator support, opt-in tool discovery with MCP meta-tools, and
  opt-in Spring AI wiring.
- **Conformance.** The official MCP conformance suite (server and client) and the A2A TCK run in
  CI against `conformance/*` apps, with baselines of expected failures.
- **Evaluation harness (`evals/tool-discovery`).**
  - Data: ToolRet and MCPToolBench++ loaders.
  - Metrics: retrieval (Recall, nDCG, MRR, Completeness) and model-in-the-loop (selection
    accuracy, wrong-tool rate, call correctness, discovery calls, tokens, cost).
  - Runs: four arms (all-tools, top-k, generic search, native adapters), confidence calibration,
    and a cost guard.
- **Examples and onboarding.** An MCP client, a plain-Java gateway (one tool over three
  protocols), a Spring Boot app, a LangChain4j agent served over A2A, a Spring AI example and an
  ARGI example; starter templates (Spring Boot MCP server, plain-Java client), a JBang demo and a
  quickstart.
- **Project.** `protoweft-bom`, the MIT license, CI on JDK 17 and 21, aggregated Javadoc, a
  documentation site (Zensical, GitHub Pages), and contribution and security guides. Maven
  Central publishing through a gated release workflow, a CycloneDX SBOM with attestations,
  Gradle dependency verification, SHA-pinned actions and OpenSSF Scorecard.

### Fixed
- The discovery search meta-tool offers models only `query` and `limit`. Its former `protocols`,
  `sources` and `read_only` arguments let GPT-5.4 filter searches into empty results in the
  model-in-the-loop evaluation; those filters are now set by the application.
- The search meta-tool returns at least 10 cards per search (a model may ask for more, not
  fewer), which lifted Claude Sonnet 5.5 by 10 points at 1,000 tools.
- MCP tool results can carry embedded resources (`Content.Resource`), and A2A inline file parts
  keep their file name. Both were found by the conformance suites.
- The OAuth client accepts authorization servers on `http://localhost` as loopback (previously
  only literal loopback IPs), which the MCP conformance suite's authorization scenarios require.
- Stopping an MCP HTTP server now releases MCP Java SDK 2.1.0's non-daemon session-sweeper thread,
  so the JVM can exit (works around an SDK issue; see `protoweft-mcp/SPEC.md`).
