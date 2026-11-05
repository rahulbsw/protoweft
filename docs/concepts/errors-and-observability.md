# Errors and observability

## One exception, neutral categories

Every AgentWeave API throws a single unchecked exception, `AgentWeaveException`. It carries:

- `category()`: a protocol-neutral `ErrorCategory`.
- `protocol()`: the protocol the error came from, if any.
- `protocolCode()`: the protocol's own error code, if any (for example a JSON-RPC code such as
  `-32602`).

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
    registry.invoke(ToolInvocation.of("files.read_file", Map.of("path", "/etc/passwd")));
} catch (AgentWeaveException e) {
    switch (e.category()) {
        case TIMEOUT, CONNECTION -> retryLater();
        case INVALID_ARGUMENTS -> log.warn("bad call ({}): {}", e.protocolCode().orElse("-"), e.getMessage());
        default -> throw e;
    }
}
```

Some protocols can't distinguish every case. For example, MCP reports both unknown tools and
invalid arguments as `-32602`, so both map to `INVALID_ARGUMENTS`. Each protocol guide documents
its mapping.

## Observability hooks

Implement `ObservationHandler` to receive start, success and error events, with latency, for every
protocol operation (`initialize`, `tools/list`, `tools/call`, agent sends, and so on):

```java
ObservationHandler metrics = new ObservationHandler() {
    @Override public void onSuccess(Observation o, Duration elapsed) {
        timer(o.protocol(), o.operation()).record(elapsed);
    }
    @Override public void onError(Observation o, Duration elapsed, Throwable error) {
        errors(o.protocol(), o.operation()).increment();
    }
};
```

- Each event carries an `Observation` with the protocol, a stable operation name and a target,
  for example a tool name.
- Handlers run on the calling thread. An exception thrown by a handler is swallowed, so telemetry
  can never break a call.
- The core depends on no telemetry library. The Spring Boot starter bridges these events to
  Micrometer when Micrometer is on the classpath.
