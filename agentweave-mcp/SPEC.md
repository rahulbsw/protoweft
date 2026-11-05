---
id: agentweave-mcp
type: module-design
status: draft
title: agentweave-mcp — MCP client connector (stdio)
parent: agentweave-architecture
---

# agentweave-mcp

## Responsibility

Lets an application use the tools of an MCP server through the core `ToolConnector` API. It is
the MCP **client** role over the **stdio** transport, built on the official MCP Java SDK
(`io.modelcontextprotocol.sdk:mcp`, pinned to **2.1.0**, the latest GA release; `2.2.0-M*`
releases are milestones).

## Boundary

- Public API: `McpClientConnector` (with its `Builder`) and `McpFeatures` (feature ids). Every
  other type is package-private. MCP SDK types never appear in a public signature.
- Uses the SDK's blocking `McpSyncClient` facade, which keeps Reactor off the AgentWeave API.

## Decisions

- **Versions come from their sources, not literals.** Wire revisions in the `ComplianceReport`
  are read from the SDK's stdio transport (`protocolVersions()`, which in 2.1.0 is `2024-11-05`
  through `2025-11-25`). The SDK version comes from a resource generated from `gradle.properties`.
  The negotiated revision is the one in the server's `initialize` result. The SDK offers its
  latest revision and rejects any server reply it doesn't support.
- **No over-advertising on the wire.** No roots, sampling, or elicitation handlers are
  registered, so the SDK sends empty client capabilities. A server that doesn't declare the
  `tools` capability makes `listTools`/`invoke` fail with `UNSUPPORTED`.
- **Error mapping** (to `ErrorCategory`; the JSON-RPC code is kept as `protocolCode`):
  - `-32602` → `INVALID_ARGUMENTS`. MCP reports both unknown tools and invalid arguments with
    this code, so the connector can't tell them apart and doesn't guess `TOOL_NOT_FOUND`.
  - `-32601` → `UNSUPPORTED`.
  - The SDK's "Unsupported protocol version" during `initialize` → `VERSION_MISMATCH`.
  - A `TimeoutException` anywhere in the cause chain → `TIMEOUT`.
  - Other failures during `initialize` → `CONNECTION`; afterwards → `PROTOCOL`.
- **Tool failures are outcomes.** A `CallToolResult` with `isError: true` becomes
  `ToolOutcome.error()`, not an exception, matching MCP's split between protocol errors and tool
  execution errors.
- **Credentials over stdio.** MCP's authorization spec covers HTTP transports; stdio servers
  take credentials from their environment. So `Credential.NamedSecret` becomes an environment
  variable of the server process, and any other credential kind fails with `AUTHENTICATION`
  before the process starts.
- **Telemetry target is the executable only.** Observation and credential `target` is the
  command, never its arguments, which may contain secrets.
- **Content mapping.** text → `Text`; image/audio → `Data` (base64 as received);
  `resource`/`resource_link` → `Unmapped` (declared unsupported, not dropped).

## Coverage

Supported and unsupported feature ids are listed in `McpFeatures`, and tests assert them. Not
implemented: Streamable HTTP and SSE transports, authorization, resources, prompts, completion,
logging, progress, cancellation, sampling, roots, elicitation, `tools/list_changed`, and
resource content.

## Verification

- Tests launch a real stdio MCP server (built on the same SDK) as a subprocess.
- Interoperability was checked manually against the official TypeScript reference server
  (`@modelcontextprotocol/server-everything`). It negotiated `2025-11-25` and listed its tools.
