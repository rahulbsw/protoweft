# Tools and the registry

A **tool** is a named, callable function with a JSON Schema describing its input. Tools look the
same whether they come from a remote MCP server, a UTCP manual, a remote A2A agent, or a method in
your own JVM.

## The tool model

| Type | Role |
|---|---|
| `ToolSpec` | Name, description, JSON input schema (a plain `Map`), and protocol-neutral metadata. |
| `ToolInvocation` | A tool name plus an argument map. Arguments may contain `null` values. |
| `ToolOutcome` | Ordered `Content` parts, an `error` flag, and an optional structured payload. |
| `Content` | `Text`, `Data` (base64 with a MIME type and an optional file name), `Json`, `Link` (by URI), `Resource` (a URI delivered with its text or bytes, such as an MCP embedded resource), or `Unmapped`. |
| `ToolSource` | Anything that can `listTools()` and `invoke(...)`. |
| `ToolConnector` | A `ToolSource` that is also a `ProtocolConnector`: a connection to one remote peer. |

`ToolOutcome.text()` joins all text parts with newlines, and `structuredContent()` returns the
structured payload if there is one. `ToolOutcome.text("…")` and `ToolOutcome.failure("…")` build
one-part outcomes.

!!! note "Tool failures are outcomes, not exceptions"
    When a tool runs and reports a failure, you get a `ToolOutcome` with `error() == true` and the
    details in its content. An `AgentWeaveException` means the call itself couldn't be made: an
    unknown tool, invalid arguments, a connection failure or a timeout.

`Content.Unmapped` stands for a part a connector can't translate. It carries the protocol's type tag, so the loss is visible instead of silent.

### Metadata

`ToolSpec.metadata()` carries protocol-neutral hints under these keys. Connectors copy only the
hints a peer actually set.

| Key | Type | Meaning | MCP mapping |
|---|---|---|---|
| `ToolSpec.META_READ_ONLY` | `Boolean` | Doesn't modify its environment. | `readOnlyHint` |
| `ToolSpec.META_DESTRUCTIVE` | `Boolean` | May perform destructive updates. | `destructiveHint` |
| `ToolSpec.META_IDEMPOTENT` | `Boolean` | Repeated calls have no extra effect. | `idempotentHint` |
| `ToolSpec.META_OPEN_WORLD` | `Boolean` | Interacts with external entities. | `openWorldHint` |
| `ToolSpec.META_TITLE` | `String` | Display title. | `title` |
| `ToolSpec.META_TAGS` | `List<String>` | Keywords, for example UTCP tool tags. | — |

## Defining local tools

Annotate public instance methods with `@AgentTool` and scan the object:

```java
public class Weather {
    public enum Unit { CELSIUS, FAHRENHEIT }
    public record Location(@ToolParam(description = "City name") String city, Optional<String> country) {}
    public record Forecast(String city, double temperature, Unit unit) {}

    @AgentTool(description = "Forecast for a location", readOnly = true, tags = {"weather"})
    public Forecast forecast(Location location, Unit unit,
                             @ToolParam(description = "Days ahead", required = false) Integer days) {
        return new Forecast(location.city(), 21.5, unit);
    }
}

LocalTools tools = LocalTools.builder().scan(new Weather()).build();
```

`@AgentTool` attributes:

| Attribute | Default | Meaning |
|---|---|---|
| `description` | (required) | What the tool does, written for a model deciding whether to call it. |
| `name` | the method name | Must match `[A-Za-z0-9_-]{1,64}`. |
| `tags` | none | Keywords, published as `META_TAGS`. [Tool discovery](../discovery/index.md) indexes them. |
| `readOnly` | `false` | Published as `META_READ_ONLY` (MCP `readOnlyHint`); discovery's `CapabilityHint.READ_ONLY`. |
| `destructive` | `false` | Published as `META_DESTRUCTIVE` (MCP `destructiveHint`); discovery's `CapabilityHint.DESTRUCTIVE`. |

`@ToolParam` goes on parameters and record components: `name`, `description`, and `required`
(default `true`).

### Generated schema

AgentWeave generates the input schema from the parameter types. For the `forecast` tool above:

```json
{
  "type": "object",
  "properties": {
    "location": {
      "type": "object",
      "properties": {
        "city": {"type": "string", "description": "City name"},
        "country": {"type": "string"}
      },
      "required": ["city"]
    },
    "unit": {"type": "string", "enum": ["CELSIUS", "FAHRENHEIT"]},
    "days": {"type": "integer", "description": "Days ahead"}
  },
  "required": ["location", "unit"]
}
```

| Java type | JSON Schema |
|---|---|
| `String`, `CharSequence`, `char` | `string` |
| `int`, `long`, `short`, `byte` (and wrappers), `BigInteger` | `integer` |
| `double`, `float` (and wrappers), `BigDecimal` | `number` |
| `boolean`, `Boolean` | `boolean` |
| An enum | `string` with an `enum` list of the constant names |
| `List<T>`, `Set<T>`, `T[]` | `array` with `items` |
| `Map<String, T>` | `object` (with `additionalProperties` unless `T` is `Object`) |
| A record | `object` with its components as properties |
| `Optional<T>` | the schema of `T`; the parameter is optional |
| `Object` | no constraint |

Every parameter is required unless it is an `Optional` or has `@ToolParam(required = false)`. Any
other type is rejected with `IllegalArgumentException` when you call `scan`, as are recursive
records.

A parameter of type `CallerIdentity` receives the authenticated caller when the tool is served over
a protected endpoint, and a parameter of type `ToolProgress` receives a
[progress reporter](#progress-and-log-messages). Neither appears in the schema.

!!! tip "Parameter names"
    Compile with `-parameters` so AgentWeave can read parameter names, or name every parameter with
    `@ToolParam(name = "...")`. Without either, `scan` fails and says so.

### Argument binding

Arguments are converted to the parameter types. Values that can't represent the type fail the call
with `AgentWeaveException` and `ErrorCategory.INVALID_ARGUMENTS`, for example `1.5` for an `int`, an
unknown enum constant, or a missing required argument. Some lenient conversions are accepted:
numeric strings for numbers, `"true"`/`"false"` for booleans, and enum names in any case.

### Return values

| The method returns | The outcome contains |
|---|---|
| `String`, a number, a boolean, a `char` or an enum | One text part |
| A record, `Map`, collection or array | Its JSON as one text part, plus the value as structured content |
| A `ToolOutcome` | That outcome, as returned |
| A `Content` | One part, as returned |
| `Optional<T>` | The contained value, converted as above |
| `void` or `null` | An empty outcome |

If the method throws, the result is a failure outcome carrying the exception message. An
`AgentWeaveException` thrown by the method propagates unchanged.

### Progress and log messages

A long-running tool can report progress and send log messages to whoever called it. Declare a
`ToolProgress` parameter:

```java
public class Importer {
    @AgentTool(description = "Imports a batch of records")
    public String importAll(@ToolParam(description = "How many records") int count, ToolProgress progress) {
        for (int i = 1; i <= count; i++) {
            progress.progress(i, (double) count, "record " + i);
        }
        progress.log(ToolProgress.Level.NOTICE, "imported " + count + " records");
        return "done";
    }
}
```

| Method | Meaning |
|---|---|
| `progress(done, total, message)` | Work done so far (should increase with each call), the total if known (else `null`), and an optional short status. |
| `log(level, message)` | A log message; `ToolProgress.Level` runs from `DEBUG` to `EMERGENCY` (the RFC 5424 levels MCP uses). |
| `info(message)` | `log(Level.INFO, message)`. |

Calls are cheap and never throw. Where the reports go depends on how the tool is called:

- **Served over MCP:** the [MCP server](../protocols/mcp.md#progress-and-logging-in-tools) sends
  them to the client as `notifications/progress` (only when the client asked for progress) and
  `notifications/message`.
- **Published as a UTCP manual:** they're dropped; UTCP has no progress channel, and the provider
  passes no reporter (the tool gets `ToolProgress.NOOP`).
- **Called directly:** pass your own reporter with
  `ToolSource.invoke(invocation, caller, progress)`. `LocalTools`, `ToolRegistry` and the
  MCP client connector forward it; sources that can't report progress inherit a default that
  ignores it. Without one, the tool gets `ToolProgress.NOOP`.

### Programmatic tools

Register tools without annotations, for example when the schema comes from elsewhere:

```java
LocalTools tools = LocalTools.builder()
        .tool("ping", "Health check", Map.of("type", "object"),
              (arguments, caller) -> ToolOutcome.text("pong"))
        .build();
```

The `ToolHandler` receives the raw argument map and the caller. A `ProgressAwareToolHandler`
`(arguments, caller, progress) -> ...` also receives the call's
[`ToolProgress`](#progress-and-log-messages); `tool(...)` accepts either. As with annotated methods,
an exception becomes a failure outcome unless it is an `AgentWeaveException`. `add(LocalTool)` and
`addAll(List<LocalTool>)` add prepared tools, for example
[LangChain4j `@Tool` methods](../integrations/langchain4j.md#tool-methods-out).

`LocalTools` is immutable and thread-safe once built. Your tool methods must be thread-safe too:
servers call them concurrently.

## The tool registry

A `ToolRegistry` combines any number of sources and addresses each tool as `<source>.<tool>`, so
names never clash:

```java
ToolRegistry registry = new ToolRegistry()
        .register("local", tools)            // LocalTools
        .register("files", mcpConnector)     // an MCP server
        .register("weather", utcpConnector)  // a UTCP manual
        .register("travel", a2aConnector);   // a remote A2A agent, as one "ask" tool

registry.listTools();   // local.forecast, files.read_file, weather.get_forecast, travel.ask, ...
ToolOutcome out = registry.invoke(ToolInvocation.of("local.forecast",
        Map.of("location", Map.of("city", "Oslo"), "unit", "CELSIUS")));
```

- Source names must match `[A-Za-z0-9_-]+`. Registering a name twice fails with
  `IllegalArgumentException`.
- The registry doesn't start connectors. Call `start()` on each connector, before or after
  registering it, but before listing its tools.
- A name without a known `<source>.` prefix fails with `TOOL_NOT_FOUND`.
- `listTools()` asks every source each time; nothing is cached. If one source fails, the whole
  listing fails.
- `unregister(name)` removes a source without closing it. `close()` closes every registered
  source that is `AutoCloseable`.
- `invoke(invocation, caller)` passes a `CallerIdentity` through to the source, and
  `invoke(invocation, caller, progress)` also passes a `ToolProgress`.

A registry is itself a `ToolSource`, so you can hand it to the
[LangChain4j](../integrations/langchain4j.md), [Spring AI](../integrations/spring-ai.md) or
[ARGI](../integrations/argi.md) adapters, serve it over [MCP](../protocols/mcp.md), or publish it
as a [UTCP manual](../protocols/utcp.md#serving-tools-as-a-utcp-manual).

!!! warning "Re-serving remote tools"
    Serving a registry that contains remote connectors turns your application into a proxy that
    spends those connectors' credentials on behalf of its own callers. Protect such an endpoint
    accordingly.
