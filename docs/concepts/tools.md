# Tools and the registry

A **tool** is a named, callable function with a JSON Schema describing its input. Tools work the
same whether they come from a remote MCP server, a UTCP manual, a remote A2A agent, or a method
in your own JVM.

## The tool model

| Type | Role |
|---|---|
| `ToolSpec` | Name, description, and JSON input schema (as a plain `Map`). |
| `ToolInvocation` | A tool name plus an argument map. |
| `ToolOutcome` | Ordered `Content` parts, an `error` flag, and an optional structured payload. |
| `Content` | `Text`, `Data` (base64 with a MIME type), `Json`, `Link` (by URI), or `Unmapped`. |
| `ToolSource` | Anything that can `listTools()` and `invoke(...)`. |
| `ToolConnector` | A `ToolSource` that is also a `ProtocolConnector`, meaning a connection to a remote peer. |

!!! note "Tool failures are outcomes, not exceptions"
    When a tool runs and reports a failure, you get a `ToolOutcome` with `error() == true` and
    the details in its content. An `AgentWeaveException` means the call itself couldn't be made:
    an unknown tool, invalid arguments, a connection failure or a timeout.

## Defining local tools

Annotate public methods with `@AgentTool` and scan the object:

```java
public class Weather {
    public enum Unit { CELSIUS, FAHRENHEIT }
    public record Location(@ToolParam(description = "City name") String city, Optional<String> country) {}
    public record Forecast(String city, double temperature, Unit unit) {}

    @AgentTool(description = "Forecast for a location")
    public Forecast forecast(Location location, Unit unit, int days) { ... }
}

LocalTools tools = LocalTools.builder().scan(new Weather()).build();
```

AgentWeave generates the input schema from the parameter types:

- `String` and `char` become `string`.
- Integer types and `BigInteger` become `integer`; `double`, `float` and `BigDecimal` become
  `number`.
- `boolean` becomes `boolean`.
- An enum becomes `string` with an `enum` list of its constant names.
- `List`, `Set` and arrays become `array`.
- `Map<String, T>` and records become `object`.
- `Optional<T>` marks the parameter as optional. Every other parameter is required, unless you set
  `@ToolParam(required = false)`.

Arguments are bound strictly: `1.5` is rejected for an `int` parameter, and an unknown enum
value is rejected too. Both fail with `ErrorCategory.INVALID_ARGUMENTS`.

Return values are converted as follows:

| The method returns | The outcome contains |
|---|---|
| `String` or a scalar | One text part |
| A record, `Map`, collection or enum | The value as structured content, plus its JSON text |
| A `ToolOutcome` or `Content` | That outcome or part, as returned |
| `void` or `null` | An empty outcome |

If the method throws, the result is a failure outcome carrying the exception message.

A parameter of type `CallerIdentity` receives the authenticated caller when the tool is served
over a protected endpoint. It doesn't appear in the schema.

!!! tip "Parameter names"
    Compile with `-parameters` so AgentWeave can read parameter names, or name each parameter with
    `@ToolParam(name = "...")`.

Tools can also be registered programmatically:

```java
LocalTools tools = LocalTools.builder()
        .tool("ping", "Health check", Map.of("type", "object"),
              (args, caller) -> ToolOutcome.text("pong"))
        .build();
```

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
registry.invoke(ToolInvocation.of("files.read_file", Map.of("path", "README.md")));
```

Source names must match `[A-Za-z0-9_-]+`. Closing the registry closes every registered source.
A registry is itself a `ToolSource`, so you can hand it to the LangChain4j adapter, serve it over
MCP, or publish it as a UTCP manual.
