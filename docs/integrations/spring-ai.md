# Spring AI

`protoweft-spring-ai` connects Protoweft to [Spring AI](https://spring.io/projects/spring-ai)
**2.0.2** in four directions, without depending on any protocol module:

1. **Tools in:** any Protoweft `ToolSource` (MCP, UTCP, A2A, local tools, a `ToolRegistry`) is
   offered to a `ChatClient` as a `ToolCallbackProvider`.
2. **Tool search:** a [`CapabilityCatalog`](../discovery/index.md) becomes Spring AI's `ToolIndex`
   for `ToolSearchToolCallingAdvisor`. The model starts with only `toolSearchTool` and receives full
   definitions just for the capabilities the catalog finds for the caller.
3. **Agents out:** a `ChatClient` becomes an `AgentHandler`, blocking or streaming, so the
   [A2A server](../protocols/a2a.md#serving-an-agent) serves it with the full task lifecycle.
4. **Tools out:** Spring AI `ToolCallback`s, including `@Tool` methods, become
   `LocalTools.LocalTool`s, so the MCP server and the UTCP provider can serve them.

```kotlin
dependencies {
    implementation(platform("io.github.rahulbsw:protoweft-bom:0.1.0-SNAPSHOT"))
    implementation("io.github.rahulbsw:protoweft-spring-ai")    // brings spring-ai-model
    implementation("io.github.rahulbsw:protoweft-mcp")          // and whichever protocols you use

    // Only for the classes that need them (see below):
    implementation("org.springframework.ai:spring-ai-client-chat:2.0.2")          // SpringAiAgents
    implementation("io.github.rahulbsw:protoweft-discovery")                          // CatalogToolIndex
    implementation("org.springframework.ai:spring-ai-tool-search-tool:2.0.2")     // CatalogToolIndex
    implementation("org.springframework.ai:spring-ai-tool-search-advisor:2.0.2")  // the advisor
}
```

`ToolCallback`, `ToolCallbackProvider`, `ToolDefinition` and `ToolContext` appear in the public API,
so the module exposes `spring-ai-model` as an `api` dependency. Everything else is `compileOnly`:
`ProtoweftToolCallbacks` and `SpringAiTools` need only `spring-ai-model`; `SpringAiAgents` needs
`spring-ai-client-chat` (which a Spring AI model starter already brings); `CatalogToolIndex` needs
`protoweft-discovery` and `spring-ai-tool-search-tool`. Add your model provider yourself.

## Tools in

```java
ToolRegistry registry = new ToolRegistry()
        .register("weather", mcpConnector)
        .register("local", LocalTools.builder().scan(new MathTools()).build());

String answer = chatClient.prompt()
        .user("What's the weather in Paris?")
        .tools(ProtoweftToolCallbacks.from(registry))
        .toolContext(Map.of(ProtoweftToolCallbacks.CALLER_KEY, caller))
        .call().content();
```

`.defaultTools(ProtoweftToolCallbacks.from(registry))` on `ChatClient.builder(...)` registers the
tools for every request instead. `ProtoweftToolCallbacks.builder(source)` offers more control:

| Method | Default | Meaning |
|---|---|---|
| `dynamic(boolean)` | `true` | `true`: list the source on every `getToolCallbacks()`, which Spring AI calls once per `ChatClient` request. `false`: list once and keep the snapshot until `refresh()`. |
| `filter(predicate)` | all tools | Offer only matching tools, tested against the Protoweft `ToolSpec`. |
| `toolNames(mapper)` | `ToolNames::sanitize` | How Protoweft names become model-facing names; the result must be unique and accepted by your provider. |
| `callerIdentity(supplier)` | — | A fixed caller for every call, ignoring the tool context. |
| `callerIdentityFrom(function)` | the `protoweft.caller` lookup | Derive the caller from Spring AI's `ToolContext` (which may be `null`). |

Caching, if you want it, belongs in the source. `originalName("weather__forecast")` returns the
Protoweft name behind a provider name, as of the latest listing.

### Tool names

`ToolNames.sanitize` uses the same algorithm as the discovery module's `ProviderToolNames` and the
LangChain4j adapter: `.` becomes `__`, other characters outside `[a-zA-Z0-9_-]` become `_`, and
names over 64 characters are cut to 55 characters plus `_` and 8 hex digits of a SHA-256 hash. So
`weather.forecast` is offered as `weather__forecast`, and a catalog capability has the same name
here as in Protoweft's own provider payloads.

Names are never parsed back: each callback is bound to its original name. Two tools that map to the
same name fail the listing with `IllegalStateException`.

### Schemas

Input schemas are serialized as JSON. An empty schema, or one without a `type`, becomes an object
schema, because model providers require one. An empty description falls back to Spring AI's
name-derived description.

### Caller and progress

The adapter overrides `ToolCallback.call(input, ToolContext)`, because Spring AI's default ignores
the tool context. The `CallerIdentity` passed to `ToolSource.invoke` is:

1. the result of `callerIdentity(...)` or `callerIdentityFrom(...)` if you configured one; otherwise
2. the `CallerIdentity` under `protoweft.caller` (`ProtoweftToolCallbacks.CALLER_KEY`) in the
   tool context; otherwise
3. `CallerIdentity.ANONYMOUS`.

A [`ToolProgress`](../concepts/tools.md#progress-and-log-messages) under `protoweft.progress`
(`PROGRESS_KEY`) is forwarded too, for example to an MCP server's progress notifications.

### Results and errors

The model receives text: text parts as they are, JSON parts serialized, links as their URI, and
binary or unmapped parts as visible bracketed markers. When there are no parts, it receives the
structured payload as JSON. This is the same rendering as the LangChain4j adapter.

Failures are thrown as Spring AI's `ToolExecutionException(definition, cause)`, the only exception
Spring AI's tool-calling manager turns into a message for the model; any other exception ends the
call. Its configured `ToolExecutionExceptionProcessor` then decides:

| Protoweft result | Cause of the `ToolExecutionException` |
|---|---|
| `ToolOutcome.error() == true` | `ToolOutcomeException`, carrying the outcome (`outcome()`) |
| `ProtoweftException` | the `ProtoweftException` itself |
| Arguments that aren't a JSON object | an `ProtoweftException` with `INVALID_ARGUMENTS` |

The default `DefaultToolExecutionExceptionProcessor` sends the message to the model so it can
recover. To end the call instead, configure one with `alwaysThrow(true)` or, for infrastructure
failures only, `rethrowExceptions(List.of(ProtoweftException.class))`.

!!! warning "Not a bean by default"
    Spring AI's MCP server auto-configuration re-serves every `ToolCallbackProvider` bean. An
    `ProtoweftToolCallbacks` bean over a registry of remote connectors would republish those tools,
    and spend their credentials, on your MCP endpoint. Create it where you build the `ChatClient`
    instead, as the [example](#example) does, or opt in through the
    [Spring Boot properties](#spring-boot).

## Tool search with the catalog

Spring AI's `ToolSearchToolCallingAdvisor` gives the model a `toolSearchTool` and loads tool
definitions on demand. `CatalogToolIndex` makes an Protoweft
[`CapabilityCatalog`](../discovery/index.md) its index, so the catalog's ranking, permission
filter, availability filter and fallback decide what the model can load.

```java
CatalogToolIndex index = CatalogToolIndex.builder(catalog)
        .callerIdentityFrom(id -> callerOf(id))     // the advisor's session id -> caller
        .build();

ChatClient chat = ChatClient.builder(model)
        .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).build())
        .defaultTools(index.toolCallbacks())        // every capability, named like the hits
        .build();

String answer = chat.prompt().user(question)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call().content();
```

- **The first request** offers the model only `toolSearchTool`. After a search, the advisor offers
  the definitions of the tools found, and the model can call them.
- **The catalog is the index.** `indexTool`, `indexTools` and `clearIndex` do nothing. `search`
  runs `CapabilityCatalog.search` with:
    - the caller from `callerIdentityFrom`, applied to the advisor's session id (by default the
      request's `ChatMemory.CONVERSATION_ID`), or anonymous;
    - the model's `maxResults`, else the builder's `limit` (default 5);
    - the request's `categoryFilter`, read as comma-separated tokens: tokens naming a catalog source
      become a source filter, tokens naming a protocol id become a protocol filter, and other
      tokens are ignored, so a category the model guesses never empties the result;
    - any `queryCustomizer((request, query) -> ...)`, for example to require read-only
      capabilities.
- **Fallback stages.** Hits at every fallback stage are returned (`ALL_SCHEMAS` returns every
  permitted capability). `NAMES_ONLY` candidates are **not** returned: the advisor loads the full
  definition of every name it gets back, and there's no text-only channel.
- **Permissions.** Capabilities the caller may not see are never named. `index.toolCallbacks()`
  covers every capability, but calls go through `CapabilityCatalog.invoke`, which re-checks
  permission and availability. The caller for a call is `protoweft.caller` from the tool context
  if present, else the same session-id function, so the search and the call see the same caller.
- **What the model doesn't see.** Spring AI's `SearchMetadata` has fields only for the search type
  (`CatalogToolIndex.SEARCH_TYPE`), the query and the time. Confidence, fallback stage and hidden
  counts go to `onResult((request, result) -> ...)`.

Builder options: `toolNames`, `callerIdentity`, `callerIdentityFrom`, `limit`, `queryCustomizer`
and `onResult`.

Limitations:

- Only catalog capabilities are searchable. Other callbacks on the same request are indexed by the
  advisor into this no-op index and are never found. Put them into the catalog, for example as a
  `LocalTools` source.
- The caller for a search is derived only from the session id, because `ToolSearchRequest`
  carries nothing else.
- Spring AI allows one tool advisor per `ChatClient`; the search advisor replaces the default
  tool-calling advisor.
- `toolCallbacks()` serializes every catalog schema on each request: CPU, not tokens, on very
  large catalogs.
- The advisor shows the model the names a search returned, but loads only those with a registered
  callback.

## Agents out: a `ChatClient` as an A2A agent

```java
ChatClient chat = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .defaultTools(ProtoweftToolCallbacks.from(registry))
        .build();

AgentHandler handler = SpringAiAgents.streamingHandler(chat);   // or SpringAiAgents.handler(chat)
AgentDescriptor card = SpringAiAgents.descriptor("Assistant", "Answers questions", "1.0.0",
        SpringAiAgents.skill("qa", "Q&A", "General questions", "qa"));

A2aAgentServer server = A2aAgentServer.builder(handler, card).publicUrl("https://agents.example.com").build();
```

- **One request per message.** The message text is the user message. The request's `contextId`
  (falling back to its `taskId`, then a random id) is passed as advisor parameter
  `ChatMemory.CONVERSATION_ID`, and the caller as tool-context entry `protoweft.caller`, so
  Protoweft tools are called on the A2A caller's behalf.
- **Memory** takes effect only if the client has a memory advisor, as above. The A2A history isn't
  replayed, because the memory owns the conversation.
- **Results.** The answer becomes one text artifact named `response`
  (`SpringAiAgents.ARTIFACT_NAME`) and the task completes. An exception, or a message with no
  text, fails the task.
- **Streaming.** `streamingHandler` uses `stream().content()`: each chunk becomes one `working`
  status carrying that fragment, and the accumulated text becomes the artifact. All task updates
  happen on the handler's thread.
- **Cancellation.** Cancelling the task disposes the subscription, which cancels the upstream
  `Flux` and stops generation.
- **Customizing.** `handler(chat, customizer)` and `streamingHandler(chat, customizer)` take a
  `BiConsumer<AgentRequest, ChatClientRequestSpec>` that runs after the defaults, for example
  `(request, spec) -> spec.system("...")`.

!!! note "Compared with spring-ai-a2a"
    The community project [spring-ai-a2a](https://github.com/spring-ai-community/spring-ai-a2a)
    also serves a `ChatClient` over A2A. Its `DefaultAgentExecutor` calls a handler that returns
    the whole answer as one `String`, adds it as a single artifact and completes the task; it
    sends no partial output while the model generates, so streaming fragments need a custom
    `AgentExecutor`. `SpringAiAgents.streamingHandler` streams each chunk as a `working` status
    and cancels generation when the task is cancelled.

## Tools out: Spring AI `@Tool` methods over MCP and UTCP

```java
class WeatherTools {
    @Tool(description = "Forecast for a city")
    public String forecast(String city, ToolContext context) {
        CallerIdentity caller = (CallerIdentity) context.getContext().get(SpringAiTools.CALLER_KEY);
        ToolProgress progress = (ToolProgress) context.getContext().get(SpringAiTools.PROGRESS_KEY);
        progress.info("looking up " + city);
        return "Sunny in " + city + " for " + caller.name();
    }
}

LocalTools tools = LocalTools.builder()
        .addAll(SpringAiTools.from(MethodToolCallbackProvider.builder().toolObjects(new WeatherTools()).build()))
        .build();

McpToolServer.http(tools);   // or UtcpManualProvider.builder(tools).build()
```

`SpringAiTools.from(...)` accepts `ToolCallbackProvider`s or `ToolCallback`s.

- **Calls.** Each callback is called with the arguments as JSON and a `ToolContext` holding the
  Protoweft caller (`protoweft.caller`) and progress reporter (`protoweft.progress`). A `@Tool`
  method reads them by declaring a `ToolContext` parameter.
- **Results.** A JSON string result is unquoted (Spring AI's default converter JSON-encodes
  strings). A JSON object or array is the text and also the structured payload. Anything else is
  text.
- **Errors.** A `ToolExecutionException` becomes a failure outcome with its message, unless its
  cause is an `ProtoweftException`, which propagates. Other exceptions become failure outcomes,
  as for every local tool.
- **Names** must match `[A-Za-z0-9_-]{1,64}` (set `@Tool(name = ...)` otherwise), and duplicates
  are rejected with `IllegalArgumentException`. `returnDirect` is kept as metadata
  `springAi.returnDirect`.

## Spring Boot

With the [Spring Boot starter](spring-boot.md) and `protoweft-spring-ai` on the classpath, three
opt-in properties wire the pieces above into Spring AI's auto-configured `ChatClient.Builder`:

```yaml
protoweft:
  spring-ai:
    tool-callbacks:
      enabled: true          # Protoweft tools on the auto-configured ChatClient.Builder
      expose: local          # local (@AgentTool methods only) | registry (every registry tool)
    tool-search:
      enabled: false         # catalog-backed tool search instead; needs protoweft.discovery.enabled
  server:
    a2a:
      enabled: true
      from-chat-client: true # serve the ChatClient as the A2A agent
```

| Property | Default | Effect |
|---|---|---|
| `protoweft.spring-ai.tool-callbacks.enabled` | `false` | Registers an `ProtoweftToolCallbacks` bean (unless you define one) and adds it to the auto-configured `ChatClient.Builder`. |
| `protoweft.spring-ai.tool-callbacks.expose` | `local` | `local`: only the application's `@AgentTool` methods (the `LocalTools` bean). `registry`: every tool in the `ToolRegistry` bean, including remote MCP, UTCP and A2A tools. |
| `protoweft.spring-ai.tool-search.enabled` | `false` | Adds `ToolSearchToolCallingAdvisor` backed by a `CatalogToolIndex` bean, plus the catalog's callbacks, to the `ChatClient.Builder`. Requires [`protoweft.discovery.enabled=true`](../discovery/index.md#spring-boot) and the tool-search jars. |
| `protoweft.server.a2a.from-chat-client` | `false` | When there's no `AgentHandler` bean, serves the application's `ChatClient` (the unique bean, else one built from `ChatClient.Builder`) as the A2A agent. Streams when `protoweft.server.a2a.streaming` is true (the default). Still needs `protoweft.server.a2a.enabled=true`. |

- **Why everything is opt-in.** Spring AI's MCP server re-serves every `ToolCallbackProvider` bean.
  Registering one automatically would silently republish your registry's remote tools, and spend
  their credentials, on any Spring AI MCP endpoint in the application.
- **Tool search replaces plain callbacks.** With both enabled, only the tool-search customizer is
  applied to the `ChatClient.Builder`: registering both would offer the same tool names twice,
  which Spring AI rejects. The `ProtoweftToolCallbacks` bean itself is still registered.
- An `AgentHandler` bean of your own always wins over `from-chat-client`.

## Example

[`examples/spring-ai-example`](../examples.md#spring-ai) is a Spring Boot application whose
`ChatClient` uses the starter's `ToolRegistry` through `ProtoweftToolCallbacks`, remembers each A2A
context, and is served over A2A with `SpringAiAgents.streamingHandler`. Its test drives it with a
scripted `ChatModel`, so it needs no API key.

## Limitations

- Binary tool content reaches the model only as a marker. `SpringAiTools` produces text and
  structured payloads only.
- Agents built with `SpringAiAgents` don't produce `input-required` or `auth-required` states, and
  tool executions aren't reported as task status.
- Tool search covers only catalog capabilities, and derives the caller from the session id only
  (see [above](#tool-search-with-the-catalog)).
