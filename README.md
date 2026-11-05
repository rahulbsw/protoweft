# AgentWeave

A small Java framework for adding interoperable agent and tool protocols (MCP, A2A, UTCP) to
Java applications, behind one protocol-neutral API.

> **Status: first vertical slice.** Only the protocol-neutral core and an **MCP stdio client** are
> implemented. A2A, UTCP, other MCP transports, and the Spring Boot starter are not built yet.
> Every connector publishes a `ComplianceReport` stating exactly what it implements.

## Modules

| Module | What it is | Depends on |
|---|---|---|
| `agentweave-core` | Protocol-neutral types and SPIs. No dependencies. | — |
| `agentweave-mcp` | MCP client connector (stdio), wrapping the official MCP Java SDK 2.x | core, `io.modelcontextprotocol.sdk:mcp` |
| `examples/mcp-client-example` | Runnable example | core, mcp |

Requirements: Java 17+. Build with the Gradle wrapper: `./gradlew build`.

## Using it (plain Java, no framework)

Application code depends on `ToolConnector` from the core. Only the line that builds the
connector names a protocol:

```java
try (ToolConnector tools = McpClientConnector
        .stdio("npx", "-y", "@modelcontextprotocol/server-everything")
        .requestTimeout(Duration.ofSeconds(30))
        .credentials((protocol, target) -> List.of(new Credential.NamedSecret("API_KEY", secret)))
        .observations(myObservationHandler)
        .build()) {
    tools.start();                                   // spawns the server, negotiates the revision
    tools.listTools().forEach(t -> System.out.println(t.name()));
    ToolOutcome out = tools.invoke(ToolInvocation.of("echo", Map.of("message", "hi")));
    if (out.error()) { /* the tool ran and reported a failure */ }
    System.out.println(out.text());
}
```

- **Errors.** A tool that runs and fails returns `ToolOutcome.error() == true`. An
  `AgentWeaveException` means the call itself couldn't be made. It carries a neutral `ErrorCategory`
  and, where available, the protocol's native error code.
- **Credentials.** Supplied through `CredentialProvider`. Over MCP stdio, `NamedSecret`s become
  environment variables of the server process, because MCP stdio servers take credentials from
  their environment. Bearer tokens are rejected, since the MCP HTTP authorization flow isn't
  implemented.
- **Observability.** Implement `ObservationHandler` to get start, success, and error events
  (with latency) for `initialize`, `tools/list`, and `tools/call`. Handler exceptions are swallowed
  and never break a call.

## Running the example

```bash
./gradlew :examples:mcp-client-example:run
# against the official reference server (needs Node):
./gradlew :examples:mcp-client-example:run --args="npx -y @modelcontextprotocol/server-everything"
```

With no arguments it launches a bundled Java demo server and calls its `greet` tool. Output
includes the compliance report:

```
SDK:               io.modelcontextprotocol.sdk:mcp 2.1.0
Wire revisions:    [2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25]
Negotiated:        2025-11-25
```

## Versions: SDK version ≠ wire revision

MCP has two independent version axes, and AgentWeave keeps them in separate fields:

- **Wire/specification revision**: a date (`2025-11-25`), negotiated per connection during
  `initialize`.
- **Java SDK version**: SemVer (`2.1.0`). SDK 2.x speaks wire revisions `2024-11-05` through
  `2025-11-25` over stdio.

"MCP V1/V2" in this project means **SDK major versions 1.x and 2.x**. This slice targets SDK 2.x.

## MCP coverage (this slice)

Implemented: stdio transport, version negotiation, `tools/list` (with pagination), `tools/call`,
text/image/audio content, and structured tool output.

Not implemented (declared in `ComplianceReport.unsupported()`): Streamable HTTP and SSE
transports, authorization, resources, prompts, completion, logging, progress, cancellation,
sampling, roots, elicitation, `tools/list_changed` notifications, and resource/resource-link
content. Resource and resource-link parts in results appear as `Content.Unmapped` instead of
being silently dropped.

## Adding a protocol connector

1. Create a module that depends on `agentweave-core` (and on the protocol's SDK, if one exists).
2. Implement `ToolConnector` for tool-calling protocols, or `ProtocolConnector` plus your own
   interface for different domains (for example A2A tasks).
3. Publish an honest `ComplianceReport`, map protocol errors onto `ErrorCategory`, and run
   operations through `ObservationHandler.observe(...)`.
4. Assert the report in tests.

## Design

Specs, with rationale and official sources:

- [`goal-and-requirements.md`](goal-and-requirements.md): goal, principles, non-goals
- [`architecture.md`](architecture.md): modules, SPIs, compatibility matrix, risks, sources
- [`agentweave-mcp/SPEC.md`](agentweave-mcp/SPEC.md): MCP connector decisions and deviations
