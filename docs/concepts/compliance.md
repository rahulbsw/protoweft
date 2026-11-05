# Versions and compliance

Every connector and server states exactly what it implements in a machine-readable
`ComplianceReport`, and the module tests assert those reports. AgentWeave never claims a feature it
hasn't implemented: a protocol feature that isn't implemented is listed as unsupported, not
silently ignored.

## The compliance report

```java
ComplianceReport report = connector.compliance();
report.sdkName();               // "io.modelcontextprotocol.sdk:mcp"
report.sdkVersion();            // "2.1.0"
report.wireRevisions();         // [2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25]
report.negotiated();            // Optional[2025-11-25], once started
report.supports(McpFeatures.TOOLS_STRUCTURED_OUTPUT);
report.require(McpFeatures.TOOLS_LIST);   // throws UNSUPPORTED if not implemented
```

| Field | Meaning |
|---|---|
| `protocol` | The `ProtocolId`: `mcp`, `a2a` or `utcp`. |
| `sdkName` | The SDK artifact the module is built on, or `none` for a module implemented from the specification. |
| `sdkVersion` | That SDK's version. For an SDK-less module, the module's own version. Never a spec revision. |
| `wireRevisions` | The protocol revisions the component can speak, oldest first. |
| `negotiatedRevision` | The revision agreed with the peer. `null` before `start()`; always `null` for servers, which negotiate per session. `negotiated()` wraps it in an `Optional`. |
| `supported` | Feature ids the component implements. |
| `unsupported` | Feature ids the protocol defines but the component doesn't implement. |

A feature id can't be in both sets. Feature-id constants live in `McpFeatures`, `A2aFeatures`
and `UtcpFeatures`; each protocol guide lists them under *Coverage*.

Where to read reports:

| Component | Report |
|---|---|
| `McpClientConnector`, `A2aAgentConnector`, `UtcpClientConnector` | `compliance()`, with the negotiated revision after `start()` |
| `McpToolServer.Http`, `McpToolServer.Stdio`, `A2aAgentServer` | `compliance()`, no negotiated revision |
| `UtcpManualProvider` | none; it declares `utcp_version` `1.1.0` in the manual it serves |
| Spring Boot | Actuator `info` under `agentweave.connectors.<name>`, for client connectors |

## SDK version is not the wire revision

Protocols version their wire format independently of the SDKs that implement them, and MCP has two
completely separate axes:

- **Wire (specification) revision:** a date such as `2025-11-25`, negotiated per connection in the
  `initialize` handshake. It marks the last backwards-incompatible change to the protocol.
- **Java SDK version:** SemVer, such as `2.1.0`. SDK `2.1.0` speaks wire revisions `2024-11-05`
  through `2025-11-25`.

AgentWeave keeps them in separate fields and never collapses them into one "version". When this
project says "MCP v1/v2", it means **SDK major versions** 1.x and 2.x; AgentWeave builds on SDK
2.x.

## Compatibility matrix

### MCP

| | Client (`McpClientConnector`) | Server (`McpToolServer`) |
|---|---|---|
| SDK | `io.modelcontextprotocol.sdk:mcp` `2.1.0` | same |
| Wire revisions, stdio | `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25` | same |
| Wire revisions, Streamable HTTP | `2025-03-26`, `2025-06-18`, `2025-11-25` | same |
| Negotiated revision | the one in the server's `initialize` result (the SDK offers `2025-11-25`) | per session, not reported |
| Features | tools, structured output, all content types, resources (with templates and subscriptions), prompts, completion, logging, progress, list-change notifications | the same, except list-change notifications (lists are snapshots) |
| Authorization (`2025-11-25`) | supported when an `HttpAuthorizer` such as `agentweave-oauth`'s `OAuthClient` is configured | reported unsupported; compose `agentweave-oauth`'s resource-server pieces in the host |
| Not supported | SSE transport; cancellation; sampling, roots and elicitation; revision `2026-07-28` (SDK `2.2.x` milestones are not used) | same |

Streamable HTTP didn't exist in `2024-11-05`, so it isn't listed for HTTP. The revisions are read
from the SDK transport in use, not hard-coded. The [MCP coverage table](../protocols/mcp.md#coverage)
lists every feature id per role.

### A2A

| | Client (`A2aAgentConnector`) | Server (`A2aAgentServer`) |
|---|---|---|
| SDK | `org.a2aproject.sdk:a2a-java-sdk-client` `1.5.0.Final` | `org.a2aproject.sdk:a2a-java-sdk-server-common` `1.5.0.Final` |
| Specification | `1.0`, JSON-RPC binding (sent as `A2A-Version: 1.0`) | same |
| Negotiated revision | the `protocolVersion` of the agent card's JSON-RPC interface; a different major version fails `start()` with `VERSION_MISMATCH` | not reported |
| Not supported | gRPC and HTTP+JSON bindings; pre-1.0 revisions (`0.3.0` and earlier) | same |

### UTCP

| | Client (`UtcpClientConnector`) | Provider (`UtcpManualProvider`) |
|---|---|---|
| SDK | `none`: no official Java SDK exists; implemented from the specification | same |
| `sdkVersion` | the `agentweave-utcp` module version (`0.1.0-SNAPSHOT`) | no report |
| Wire revisions | `1.0`, `1.1` (the manual format didn't change; 1.1 client rules apply to both) | serves `utcp_version` `1.1.0` |
| Negotiated revision | the manual's declared `utcp_version`, as declared and not validated, like the Python reference | — |
| Reference checked against | python-utcp core `1.2.0`, `utcp-http` `1.1.15` | same |

### Frameworks and runtime

| Component | Version |
|---|---|
| Java | 17 or newer |
| Spring Boot (starter) | 4.x, built and tested on `4.1.2`; 3.x isn't supported |
| LangChain4j (adapter) | `1.23.0` |
| Spring AI (adapter) | `2.0.2` |
| ARGI (`agentweave-argi`) | `2.1.0-RC1` (Spring AI based; ARGI itself is built on Spring AI `2.0.1`, aligned to `2.0.2`) |
| ARGI (`agentweave-argi-neutral`, unpublished) | `2.1.0-RC2-SNAPSHOT` at commit `a869538fcee17230bc8f20f6ed76c0d15926220e`, built from source |
| Nimbus (OAuth module, internal) | `oauth2-oidc-sdk` `11.39`, `nimbus-jose-jwt` `10.10` |
| Jetty (embedded host) | `12.1.14` (Jakarta EE 11, Servlet 6.1) |
| Servlet API (any host) | compiled against Jakarta Servlet `6.1.0` |
| Jackson (UTCP module, internal) | `3.1.4` |

These versions are pinned in the build's `gradle.properties` (the ARGI snapshot commit in
`scripts/install-argi-snapshot.sh`). The BOM aligns the published AgentWeave modules; the protocol
SDKs arrive transitively at the versions above.
