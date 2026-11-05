# Errors and observability

## Outcomes versus exceptions

AgentWeave distinguishes two kinds of failure:

- **The tool ran and failed.** You get a `ToolOutcome` with `error() == true`; the details are in
  its content. Examples: an MCP `CallToolResult` with `isError`, a UTCP tool answering HTTP 500,
  an A2A task that ended `failed`.
- **The call couldn't be made.** An `AgentWeaveException` is thrown. Examples: an unknown tool,
  rejected arguments, a connection failure, a timeout, a missing UTCP variable.

Builders reject invalid arguments (a blank name, a relative URL, a non-positive timeout)
immediately with `IllegalArgumentException` or `IllegalStateException`. Configuration problems
detected later, such as an A2A server built without a public URL or a missing UTCP variable, are
`AgentWeaveException`s with `CONFIGURATION`.

## One exception, neutral categories

`AgentWeaveException` is unchecked. It carries:

- `category()`: a protocol-neutral `ErrorCategory`.
- `protocol()`: the `ProtocolId` the error came from, if any.
- `protocolCode()`: the protocol's own error code, if any: a JSON-RPC code such as `-32602`, or
  an HTTP status such as `401`.

| Category | Meaning |
|---|---|
| `CONFIGURATION` | Misconfigured, or used before `start()`. |
| `CONNECTION` | The transport couldn't be established or was lost. |
| `AUTHENTICATION` | Credentials were missing, rejected, or unusable on this transport. |
| `PROTOCOL` | The peer violated the protocol or returned an unexpected protocol error. |
| `VERSION_MISMATCH` | No common protocol revision. |
| `TOOL_NOT_FOUND` | The tool doesn't exist. |
| `INVALID_ARGUMENTS` | The arguments were rejected. |
| `TIMEOUT` | The operation didn't finish in time. |
| `UNSUPPORTED` | The feature isn't implemented by the connector, or isn't offered by the peer. |
| `INTERNAL` | Anything else. |

```java
try {
    ToolOutcome out = registry.invoke(ToolInvocation.of("files.read_file", Map.of("path", "README.md")));
    if (out.error()) {
        System.err.println("the tool reported a failure: " + out.text());
    }
} catch (AgentWeaveException e) {
    switch (e.category()) {
        case TIMEOUT, CONNECTION -> retryLater();
        case INVALID_ARGUMENTS -> System.err.println("bad call (" + e.protocolCode().orElse("-") + "): " + e.getMessage());
        default -> throw e;
    }
}
```

Protocols can't always distinguish every case. MCP, for example, reports both unknown tools and
invalid arguments as JSON-RPC `-32602`, so the MCP client maps both to `INVALID_ARGUMENTS` rather
than guessing. Each protocol guide has the full mapping:
[MCP](../protocols/mcp.md#error-mapping), [A2A](../protocols/a2a.md#error-mapping),
[UTCP](../protocols/utcp.md#errors).

`ComplianceReport.require(feature)` throws `UNSUPPORTED` when a connector doesn't implement a
feature, so you can fail fast before relying on it.

## Observability hooks

Implement `ObservationHandler` to receive start, success and error events, with latency, for every
protocol operation. All three methods have empty defaults, so override only what you need.

```java
ObservationHandler logging = new ObservationHandler() {
    @Override
    public void onSuccess(Observation o, Duration elapsed) {
        System.out.printf("%s %s %s ok in %d ms%n", o.protocol(), o.operation(), o.target(), elapsed.toMillis());
    }

    @Override
    public void onError(Observation o, Duration elapsed, Throwable error) {
        System.out.printf("%s %s %s failed: %s%n", o.protocol(), o.operation(), o.target(), error.getMessage());
    }
};
```

Pass it to a builder: `observations(handler)` on the MCP, A2A and UTCP builders and on
`McpToolServer`.

Each event carries an `Observation`: the protocol, a stable operation name, and a target.

| Component | Operations | Target |
|---|---|---|
| `McpClientConnector` | `initialize`, `tools/list`, `tools/call` | the executable (stdio) or base URL (HTTP) for `initialize`; the tool name for `tools/call` |
| `McpToolServer` | `tools/call` | the tool name |
| `A2aAgentConnector` | `agent-card`, `SendMessage`, `SendStreamingMessage`, `SubscribeToTask`, `GetTask`, `CancelTask`, `GetExtendedAgentCard` | the base URL, or the task id |
| `A2aAgentServer` | the JSON-RPC method (for example `SendMessage`), and `agent/execute` around each handler run | the task id for `agent/execute` |
| `UtcpClientConnector` | `register_manual`, `call_tool`, `search_tools` | the manual name, `<manual>.<tool>`, or the query |

- Targets never carry secrets: no command-line arguments, no URL user info, query or fragment, and
  never tool arguments.
- Handlers are called around the operation, normally on the thread performing it. The MCP server
  is reactive, so its start and stop events may arrive on different threads.
- An exception thrown by a handler is swallowed, so telemetry can never break a call.
- The core depends on no telemetry library. The [Spring Boot starter](../integrations/spring-boot.md#micrometer)
  bridges these events to Micrometer observations.

`ObservationHandler.observe(handler, observation, action)` is the helper every connector uses; call
it from your own connector so it reports the same way.
