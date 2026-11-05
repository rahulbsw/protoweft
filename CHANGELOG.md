# Changelog

All notable changes to AgentWeave are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `agentweave-core`: the protocol-neutral API.
  - Tools: `ToolSource`, `ToolConnector`, and the namespaced `ToolRegistry`.
  - Local tools: `LocalTools` with `@AgentTool`/`@ToolParam` and JSON Schema generated from
    parameter types.
  - Agents: `AgentHandler` and `AgentTaskContext` (full task lifecycle), plus `AgentConnector`.
  - Cross-cutting: `ComplianceReport`, the `CredentialProvider` and `InboundAuthenticator` hooks,
    `ObservationHandler`, and `AgentWeaveException` with `ErrorCategory`.
- `agentweave-server` and `agentweave-server-jetty`: the servlet hosting contract and an
  embedded Jetty 12 host.
- `agentweave-mcp`: an MCP client over stdio, built on MCP Java SDK 2.1.0.
- `agentweave-bom`: version alignment for every module.
- Project files: MIT license, CI, and contribution and security guides.
