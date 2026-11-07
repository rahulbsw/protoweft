# Tools and the registry

A **tool** is a named, callable function with a JSON Schema describing its input. Tools look the
same whether they come from a remote MCP server, a UTCP manual, a RAP tool server, a remote A2A
agent, or a method in your own JVM.

## The tool model

| Type | Role |
|---|---|
| `ToolSpec` | Name, description, JSON input schema (a plain `Map`), and protocol-neutral metadata. |
| `ToolInvocation` | A tool name plus an argument map. Arguments may contain `null` values. |
| `ToolOutcome` | Ordered `Content` parts, an `error` flag, an optional structured payload, and an optional [subscription](#subscriptions). |
| `Content` | `Text`, `Data` (base64 with a MIME type and an optional file name), `Json`, `Link` (by URI), `Resource` (a URI delivered with its text or bytes, such as an MCP embedded resource), or `Unmapped`. |
| `ToolSource` | Anything that can `listTools()` and `invoke(...)`. |
| `ToolConnector` | A `ToolSource` that is also a `ProtocolConnector`: a connection to one remote peer. |
| `ToolSubscription`, `ToolEvent` | Events a tool keeps sending after its first result (see [Subscriptions](#subscriptions)). |
| `ToolContext`, `ToolContexts` | Protocol-specific per-call contexts handed to tools (see [Per-call tool contexts](#per-call-tool-contexts)). |

`ToolOutcome.text()` joins all text parts with newlines, and `structuredContent()` returns the
structured payload if there is one. `ToolOutcome.text("…")` and `ToolOutcome.failure("…")` build
one-part outcomes.

!!! note "Tool failures are outcomes, not exceptions"
    When a tool runs and reports a failure, you get a `ToolOutcome` with `error() == true` and the
    details in its content. An `ProtoweftException` means the call itself couldn't be made: an
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

Protoweft generates the input schema from the parameter types. For the `forecast` tool above:

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
a protected endpoint, a parameter of type `ToolProgress` receives a
[progress reporter](#progress-and-log-messages), and a parameter whose type is an interface
extending `ToolContext` receives that [per-call context](#per-call-tool-contexts). None of them
appears in the schema.

!!! tip "Parameter names"
    Compile with `-parameters` so Protoweft can read parameter names, or name every parameter with
    `@ToolParam(name = "...")`. Without either, `scan` fails and says so.

### Argument binding

Arguments are converted to the parameter types. Values that can't represent the type fail the call
with `ProtoweftException` and `ErrorCategory.INVALID_ARGUMENTS`, for example `1.5` for an `int`, an
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
`ProtoweftException` thrown by the method propagates unchanged.

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
- **Published as a UTCP manual or served over RAP:** they're dropped; neither protocol has a
  progress channel, and the server passes no reporter (the tool gets `ToolProgress.NOOP`).
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
[`ToolProgress`](#progress-and-log-messages), and a `ContextualToolHandler`
`(arguments, caller, progress, contexts) -> ...` also its [`ToolContexts`](#per-call-tool-contexts);
`tool(...)` accepts any of them. As with annotated methods,
an exception becomes a failure outcome unless it is an `ProtoweftException`. `add(LocalTool)` and
`addAll(List<LocalTool>)` add prepared tools, for example
[LangChain4j `@Tool` methods](../integrations/langchain4j.md#tool-methods-out).

`LocalTools` is immutable and thread-safe once built. Your tool methods must be thread-safe too:
servers call them concurrently.

## Per-call tool contexts

Some protocols give a tool more than its arguments, the caller and progress reporting. RAP, for
example, lets a running tool start a subscription or ask the user to choose. Such a protocol defines
a **tool context**: an interface extending `ToolContext`, such as RAP's
[`RapCallContext`](../protocols/rap.md#tools-that-use-rap-rapcallcontext). The core knows only the
SPI:

| Type | Role |
|---|---|
| `ToolContext` | Marker interface for a protocol's per-call context. `available()` is `true` for a real context. |
| `ToolContexts` | The immutable set of contexts for one call, keyed by interface type: `of(type, value)`, `with(type, value)`, `get(type)` (an `Optional`), `resolve(type)` (never `null`), and `EMPTY`. |
| `ContextualToolHandler` | A programmatic tool's handler that receives the call's `ToolContexts`. |

- An `@AgentTool` method receives a context by declaring a parameter of its interface type. The
  parameter type must be an interface; `scan` rejects a class.
- A programmatic tool uses a `ContextualToolHandler` and `contexts.resolve(Type.class)` (RAP also
  offers `RapCallContext.from(contexts)`).
- When the protocol serving the call doesn't provide the context (another protocol, or a direct
  call), the tool gets a **stand-in** whose `available()` is `false` and whose other methods throw
  `ProtoweftException` with `UNSUPPORTED`. Check `available()` to behave sensibly everywhere.
- Servers pass contexts with `ToolSource.invoke(invocation, caller, progress, contexts)`.
  `LocalTools`, `ToolRegistry` and the discovery catalog pass them on; other sources inherit a
  default that drops them.

## Subscriptions

A tool call can keep delivering **events** after its first result, for example notifications from
a watched repository. The first result is an ordinary `ToolOutcome`; its `subscription()` returns
an `Optional<ToolSubscription>`:

| Member | Meaning |
|---|---|
| `id()` | The protocol's id for the subscription (for RAP, the tool call id). |
| `events()` | A `java.util.concurrent.Flow.Publisher<ToolEvent>`. It accepts **one** subscriber (a second gets `onError`), buffers events until it subscribes, completes after a last event or `cancel()`, and fails if the subscription is lost. Cancelling the `Flow.Subscription` cancels the subscription. |
| `cancel()` | Stops the subscription: tells the tool (best effort) and completes `events()`. Idempotent. |
| `isActive()` | `true` until a last event, a cancel or a failure. |

A `ToolEvent(content, last, metadata)` carries `Content` parts (`text()` joins the text parts), a
`last` flag for the final event, and protocol facts as metadata (RAP adds `associative` and
`final`). `ToolEvent.text("…")` builds a one-part event.

```java
ToolOutcome out = registry.invoke(ToolInvocation.of("repos.watch", Map.of("repo", "acme/app")));
out.subscription().ifPresent(subscription -> subscription.events().subscribe(new Flow.Subscriber<ToolEvent>() {
    @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    @Override public void onNext(ToolEvent event) { System.out.println(event.text()); }
    @Override public void onError(Throwable error) { }
    @Override public void onComplete() { }
}));
```

- **Producing:** `BufferedToolSubscription(id, onCancel)` is the producer side: `emit(event)`,
  `complete()` and `fail(error)`, delivering in order on the emitting or requesting thread. Attach it
  with `outcome.withSubscription(subscription)`; the three-argument `ToolOutcome` constructor and
  the factories create outcomes without one.
- **Who fills it in:** only [RAP](../protocols/rap.md#subscriptions) today, on the client
  (`RapClientConnector`), and on the server, where a tool subscribes through `RapCallContext`.
  `LocalTools`, `ToolRegistry` and the discovery catalog return the source's outcome object, so the
  subscription passes through, and `RapToolServer` re-serves one as RAP subscription events.
- **Everywhere else** (the MCP and UTCP servers, and the LangChain4j, Spring AI, ARGI and native
  discovery adapters) only the first result is used: they call `ToolOutcome.dropSubscription()`,
  which cancels the subscription so the tool stops producing events nobody reads. Your own code
  that ignores `subscription()` should do the same.

## The tool registry

A `ToolRegistry` combines any number of sources and addresses each tool as `<source>.<tool>`, so
names never clash:

```java
ToolRegistry registry = new ToolRegistry()
        .register("local", tools)            // LocalTools
        .register("files", mcpConnector)     // an MCP server
        .register("weather", utcpConnector)  // a UTCP manual
        .register("repos", rapConnector)     // a RAP tool server
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
- `invoke(invocation, caller)` passes a `CallerIdentity` through to the source,
  `invoke(invocation, caller, progress)` also passes a `ToolProgress`, and
  `invoke(invocation, caller, progress, contexts)` also the [tool contexts](#per-call-tool-contexts).
- Outcomes come back as the source returned them, [subscription](#subscriptions) included.

A registry is itself a `ToolSource`, so you can hand it to the
[LangChain4j](../integrations/langchain4j.md), [Spring AI](../integrations/spring-ai.md) or
[ARGI](../integrations/argi.md) adapters, serve it over [MCP](../protocols/mcp.md) or
[RAP](../protocols/rap.md#server), or publish it as a
[UTCP manual](../protocols/utcp.md#serving-tools-as-a-utcp-manual).

!!! warning "Re-serving remote tools"
    Serving a registry that contains remote connectors turns your application into a proxy that
    spends those connectors' credentials on behalf of its own callers. Protect such an endpoint
    accordingly.
