---
id: protoweft-langchain4j
type: module-design
status: draft
title: protoweft-langchain4j — LangChain4j adapter (tools in, agents out, @Tool out)
parent: protoweft-architecture
---

# protoweft-langchain4j

## Responsibility

Shows that Protoweft plugs into a non-Spring agent framework, in five directions, against
**LangChain4j 1.23.0**:

1. **Tools in** — any Protoweft `ToolSource` (MCP, UTCP, A2A, local tools, a `ToolRegistry`) is
   offered to a LangChain4j AI Service as a `ToolProvider`.
2. **Agents out** — a LangChain4j agent (an AI Service method) becomes a core `AgentHandler`, so
   an Protoweft agent server (for example the A2A server) can serve it.
3. **@Tool out** — LangChain4j `@Tool` methods become `LocalTools.LocalTool`s, so Protoweft tool
   servers (MCP server, UTCP manual) can serve them.
4. **Tool search** — an Protoweft `CapabilityCatalog` answers LangChain4j's tool search, so a
   model starts with one search tool and receives full schemas only for the capabilities found.
5. **Embeddings** — any LangChain4j `EmbeddingModel` (a hosted one the application already uses,
   such as OpenAI, Ollama or Vertex AI, or one of LangChain4j's in-process models) becomes a
   discovery `Embedder`, so a `CapabilityCatalog` ranks semantically with it.

## Boundary

- Depends on `protoweft-core`, `protoweft-discovery` and `dev.langchain4j:langchain4j` only
  (`api` scope: their types appear in the public API). Jackson 2 is used internally, unversioned,
  so the version LangChain4j brings governs. No dependency on any protocol module.
- Public API (package `io.github.rahulbsw.protoweft.langchain4j`):
  - `ProtoweftToolProvider implements ToolProvider` — `from(ToolSource)`,
    `builder(ToolSource)` with `dynamic`, `filter`, `toolNames`, `callerIdentity`,
    `callerIdentityFrom`, `alwaysVisible`, `asyncExecutor`; `originalName(String)`;
    `TOOL_NAME_METADATA`.
  - `CatalogToolSearchStrategy implements ToolSearchStrategy` — `from(CapabilityCatalog)`,
    `builder(CapabilityCatalog)` with `toolName`, `toolDescription`, `limit`, `toolNames`,
    `callerIdentity`, `callerIdentityFrom`, `queryCustomizer`, `alwaysVisible`, `asyncExecutor`;
    `toolProvider()`; `DEFAULT_TOOL_NAME` (`search_capabilities`).
  - `LangChain4jAgents` — `handler(Conversation)`, `streamingHandler(StreamingConversation)`,
    `descriptor(...)`, `skill(...)`, `ARTIFACT_NAME`. `Conversation` is
    `String chat(String conversationId, String userMessage)`; `StreamingConversation` returns a
    `TokenStream`. An AI Service method `chat(@MemoryId String, @UserMessage String)` fits either
    as a method reference.
  - `LangChain4jTools.from(Object...)` → `List<LocalTools.LocalTool>`; `CALLER_PARAMETER`.
  - `JsonSchemas.toJsonObjectSchema(Map)` / `toMap(JsonObjectSchema)`; `ToolNames.sanitize`.
  - `LangChain4jEmbedder implements Embedder` — `of(EmbeddingModel)`, `builder(EmbeddingModel)`
    with `name`, `queryPrefix`, `documentPrefix`, `batchSize`, `dimension`; accessors `model()`,
    `queryPrefix()`, `documentPrefix()`. (Moved from `protoweft-discovery-embeddings`, whose
    offline embedder no longer uses LangChain4j.)

Semantic discovery with a hosted model:

```java
Embedder hosted = LangChain4jEmbedder.builder(openAiEmbeddingModel).dimension(1536).batchSize(256).build();
CapabilityCatalog catalog = CapabilityCatalog.builder().source("svc", tools).embedder(hosted).build();
```

Tool search over a catalog (`ToolSearchStrategy` is `@Experimental` in LangChain4j):

```java
CatalogToolSearchStrategy search = CatalogToolSearchStrategy.builder(catalog)
        .callerIdentityFrom(ctx -> callerOf(ctx)).build();
Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .toolProvider(search.toolProvider())   // every capability, searchable
        .toolSearchStrategy(search)            // the catalog answers the searches
        .build();
```

Serving an agent over A2A (the server lives in `protoweft-a2a`):

```java
AgentHandler handler = LangChain4jAgents.handler(assistant::chat);
AgentDescriptor card = LangChain4jAgents.descriptor("Assistant", "Answers questions", "1.0.0",
        LangChain4jAgents.skill("qa", "Q&A", "General questions", "qa"));
// pass card + handler to A2aAgentServer
```

## Decisions

- **Tool names.** Providers commonly restrict tool names to `^[a-zA-Z0-9_-]{1,64}$` (OpenAI's
  limit), while registry names are `<source>.<tool>`. `ToolNames.sanitize` maps `.` → `__`, other
  disallowed characters → `_`, and cuts names over 64 characters to 55 plus `_` and an 8-hex hash.
  The reverse is not parsed: each `ToolExecutor` is bound to its original name, which is also put
  in the specification's metadata (`protoweft.toolName`; LangChain4j does not send metadata to
  providers by default). Two tools that map to the same name fail `provideTools` with
  `IllegalStateException` instead of one silently shadowing the other. The mapper is replaceable.
- **Listing.** Tools are listed from the source on every `provideTools` call (once per AI Service
  invocation; before every model call when `dynamic(true)`, LangChain4j's `isDynamic`, default
  `false`). Caching is the source's job, as in `langchain4j-mcp`.
- **Schema conversion goes through LangChain4j's public JSON API.** Core map → JSON →
  `ToolSpecification.fromJson`; `ToolSpecification.toJson` → map for the other direction. These
  call LangChain4j's own `JsonSchemaElementJsonUtils` (`@Internal`, so not called directly), which
  keeps keywords without a typed model (`format`, `minimum`, `oneOf`, …) as `JsonRawSchema`, so
  nested schemas round-trip losslessly. Reimplementing `langchain4j-mcp`'s `ToolSpecificationHelper`
  was rejected: it drops those keywords. Only the root must be a typed `JsonObjectSchema`, so the
  root is normalized: missing `type` means object, a non-object `type` is rejected, draft-07
  `definitions` become `$defs` (with `$ref`s rewritten), schema-valued `additionalProperties` becomes
  `true` (as `langchain4j-mcp` does), and other root keywords (`$schema`, `title`, `allOf`, …) are
  dropped.
- **Error handling follows LangChain4j's own conventions.**
  - A tool-reported failure (`ToolOutcome.error()`) is a `ToolExecutionResult` with `isError`
    set and the outcome text, unprefixed. That's what `DefaultToolExecutor` does for a throwing
    `@Tool`, and LangChain4j sends it to the model as an error tool result.
  - An `ProtoweftException` is not turned into text. It is rethrown wrapped, so the AI Service's
    configured `ToolArgumentsErrorHandler`/`ToolExecutionErrorHandler` decides what happens, and
    the handler receives the `ProtoweftException` itself. `INVALID_ARGUMENTS` (and arguments
    that aren't a JSON object) become `ToolArgumentsException`; the default handler fails a sync
    invocation. Other categories become `ToolExecutionException`; the sync default sends the
    message to the model, and the async default fails the invocation.
  - `LangChain4jTools` runs `@Tool` methods through `DefaultToolExecutor`. An exception from
    the method becomes a failure outcome. An argument-binding error that escapes it becomes
    `ProtoweftException(INVALID_ARGUMENTS)`, as `LocalTools.scan` does.
- **Result text.** The model gets text: text parts as-is, JSON parts serialized, links as their URI,
  and binary or unmapped parts as visible bracketed markers. They're never dropped silently. When
  there are no parts, it gets the structured payload as JSON. The full `ToolOutcome` is attached as
  `ToolExecutionResult.result()` for application code, and LangChain4j doesn't send it to the model.
- **Async.** `executeAsync` is overridden, since LangChain4j's default fails asynchronous AI
  Services. The blocking call runs on a configurable `Executor`. The default is a shared cached
  pool of daemon threads, so blocking network calls never occupy the common pool.
- **Caller identity.** On the way in, a `Function<InvocationContext, CallerIdentity>` (or a
  supplier) chooses the caller passed to `ToolSource.invoke`. The default is anonymous. On the way
  out, the Protoweft caller reaches a `@Tool` method as `InvocationParameters` entry
  `protoweft.caller`.
- **Memory id.** The conversation id is the request's `contextId`, falling back to the `taskId`
  (then a random id). It's the protocol's conversation, so LangChain4j chat memory follows A2A
  contexts across tasks. The request's `history` isn't replayed, because chat memory owns the
  conversation.
- **Agent results.** The answer becomes one text artifact (`response`), then the task is
  completed. An exception fails the task with its message. While streaming, each partial
  response is sent with `textDelta` and no final artifact is added (the fragments are the answer);
  if the model streamed nothing, the complete response's text becomes the `response` artifact. Stream
  callbacks are queued and replayed on the handler's thread, so `AgentTaskContext` is only called
  from one thread. Cancellation stops listening, but LangChain4j's stream isn't aborted.
- **Tool calls reported.** The streaming handler hooks `beforeToolExecution`/`onToolExecuted` and
  reports `toolCall`/`toolResult` (matched by request equality; the request id, else a new one).
- **Over AG-UI** (decided with the user): AI Services cache one `ChatMemory` per id, so
  `LangChain4jAgents.chatMemory(fallback)` returns a `FrontendHistory` provider. The AI Service is
  built with it, and the same instance goes to `handler(…, memory)` / `streamingHandler(…, memory)`.
  Its in-flight runs are scoped to that instance, not global, so two services sharing a
  conversation id never read or write each other's runs. A second overlapping run of one
  conversation fails the task rather than overwrite the first (review finding on #12). While a run
  is in flight for an id, its memories serve that run's conversation (`AgUiHistory`: messages before
  the last user message, context entries as a leading system message, assistant tool calls and
  tool results kept) and otherwise delegate to `fallback`, which AG-UI runs never write. If an
  AG-UI run never read it, a warning is logged once (the AI Service was built without the
  wrapper). `protoweft-agui` is `compileOnly`; `AgUiHistory` loads only after a class-presence
  check. **Deferred:** the application's own tools; an AI Service needs a new user message to
  continue after a tool result, and driving the model in Protoweft would break goal principle 11.

- **Tool search maps onto LangChain4j's own mechanism, not onto `DiscoveryTools`.** LangChain4j
  1.23.0 (`dev.langchain4j.service.tool.search`, since 1.12.0) works like this:
  - The AI Service's tools (static ones and those of non-dynamic `ToolProvider`s) are its
    *available* tools. Each is searchable unless its specification's metadata
    `ToolSpecification.METADATA_SEARCH_BEHAVIOR` is `SearchBehavior.ALWAYS_VISIBLE` (what
    `@Tool(searchBehavior = ALWAYS_VISIBLE)` sets).
  - The first request carries only the always-visible tools plus the strategy's
    `getToolSearchTools`.
  - `search(ToolSearchRequest)` gets the model's tool call, the searchable tools not yet found
    and the `InvocationContext`. It returns `ToolSearchResult(foundToolNames, text)`.
  - `ToolSearchService` stores the names in the result message's attribute `found_tools`.
    Later requests in the loop, and later invocations while that message is in chat memory,
    carry those tools' full specifications. A found name that isn't an available tool throws.

  So `CatalogToolSearchStrategy` does this:
  - **Search tool.** It offers one tool, `search_capabilities`, with the `DiscoveryTools` input
    schema (`query`, `limit`).
  - **Search.** It parses the arguments with `DiscoveryTools.query`, so the model's `limit` applies
    and any other key is ignored (see `protoweft-discovery`).
    The caller comes from the `InvocationContext` function, so the caller's scopes apply too. A
    `queryCustomizer` can tighten the query per application, and `limit` is the default when the
    model passes none. Then it calls `catalog.search`.
  - **Found tools.** Hits are mapped to LLM names with the same mapper as the provider
    (`ToolNames.sanitize`). Only names among the request's searchable tools are returned as found,
    so LangChain4j never sees an unknown name. Hits that were found earlier are listed as
    "Already available".
  - **Fallback stages.** Hits at `BROADENED` and `ALL_SCHEMAS` become found tools, so
    `ALL_SCHEMAS` reproduces the all-tools baseline. `NAMES_ONLY` candidates are only listed in
    the text, without loading them, because schemas are sent only for what is selected. The model
    can search for one by name.
  - **Result text.** It always states confidence, stage and mode, plus matches hidden as
    unavailable, per source. Matches hidden by permission are never mentioned.
  - **Errors.** Invalid arguments throw `LlmVisibleToolExecutionException`, which the model
    sees, as `SimpleToolSearchStrategy` does by default.
  - **`toolProvider()`.** It is an `ProtoweftToolProvider` over the catalog. Schemas come from
    the catalog's `SchemaCache`, and calls go through `CapabilityCatalog.invoke`, which re-checks
    permission and availability. It uses the same name mapper and caller function.
  - **Static provider.** The provider is static (`isDynamic() == false`), because
    `ToolService.refreshDynamicProviders` adds dynamic providers' tools as always effective,
    which would bypass search.
  - **Always visible.** `alwaysVisible(Predicate<ToolSpec>)` on either builder sets
    `ALWAYS_VISIBLE`. That is the provider-side equivalent of the `@Tool` attribute.
  - **No `DiscoverySession`.** LangChain4j's `found_tools` in chat memory already plays its role.
    The provider lists every capability, whoever the caller, because full specifications reach
    the model only after a permission-filtered search, and calls are re-checked.
  - **Rejected fallback.** A dynamic `ToolProvider` exposing the three `DiscoveryTools`
    meta-tools was the alternative if the strategy API couldn't carry this. It isn't needed, and
    `DiscoveryTools` can still be offered through `ProtoweftToolProvider.from(DiscoveryTools.of(catalog))`.

- **Embeddings go through `embedAll`.** In LangChain4j 1.23.0 every `EmbeddingModel` method is
  `default`; `embedAll` and `doEmbed` have mutually recursive defaults. The in-process models
  override only `embedAll`; newer hosted providers override `doEmbed`, which the default
  `embedAll` reaches. `embedAll` is therefore the one entry point that works for both; single
  texts are a one-element batch. `embedDocuments` splits into `batchSize` chunks (default: one
  call), returns `List.of()` for an empty list without calling the model (the in-process models
  reject empty lists), and fails with `IllegalStateException` if the model returns a different
  number of vectors.
- **Embedding prefixes and checks.** `embedQuery` prepends the query prefix,
  `embedDocument`/`embedDocuments` the document prefix; both default to empty (bge wants a query
  instruction, e5 `"query: "`/`"passage: "`). Null text → `NullPointerException`, blank text →
  `IllegalArgumentException`, for every model alike. Vectors are copied before return. When the
  dimension is known (from the builder, or after `dimension()` has asked the model once and
  cached it), every vector is checked against it.

## Coverage and limitations

- Not mapped: binary tool content (images) into LangChain4j's multimodal tool results
  (`resultContents` is `@Experimental` and provider-dependent). `requireInput`/`requireAuth`
  flows aren't produced. Tool-execution events aren't reported as task status.
- `LangChain4jTools` gives text/image content only, with no structured payload. `@Tool` names
  must already match `[A-Za-z0-9_-]{1,64}`.
- Root-level schema keywords beyond `JsonObjectSchema`'s model are dropped (see above).
- Tool search depends on LangChain4j's `@Experimental` API. `toolProvider()` converts every
  catalog schema to a `ToolSpecification` once per AI Service invocation, which costs CPU (not
  tokens) on very large catalogs.

## Verification

`./gradlew :protoweft-langchain4j:build` runs JUnit tests with no network and no real LLM:

- A scripted `ChatModel` drives real `AiServices`, sync and `CompletableFuture`. It calls a
  sanitized registry tool with parsed arguments, and the model receives the result, error
  results, and exception routing through the default handlers.
- Also covered:
  - Name sanitizing and clash detection.
  - Caller passthrough and the async executor.
  - Schema fidelity in both directions: nested objects, arrays, enums, required fields,
    descriptions, raw keywords, and root normalization.
  - `@Tool` objects through `LocalTools.invoke`.
  - Agent handlers, blocking and over a real streaming AI Service, with a fake `AgentTaskContext`.
  - `LangChain4jEmbedderTest` (fake models): query prefix on queries only, document prefix on
    documents only, single batch by default, ordered `batchSize` chunks, empty list without a
    model call, dimension asked once and cached, explicit dimension not asking the model and
    enforced, blank/null rejected, vectors copied, wrong vector count rejected, a
    `doEmbed`-only model works, default naming.
  - Tool search over a catalog, with a scripted model making a sequence of calls
    (`CatalogToolSearchStrategyTest`, 8 tests):
    - The first request offers only `search_capabilities`. The found tool's full specification
      is in the next request, the model calls it, and the catalog tool runs. A tool never
      searched is never offered.
    - Found tools persist into the next invocation through chat memory.
    - A scoped capability is found only for a caller holding the scope, and its existence
      doesn't leak to others.
    - `alwaysVisible` tools are in the first request.
    - The model's `limit` applies; invented filters (`read_only`, `sources`) are ignored.
    - `NAMES_ONLY` lists candidates without loading them, and `ALL_SCHEMAS` loads everything.
    - Unavailable sources are reported, tools found earlier are not found again, and invalid
      arguments are visible to the model.

## Sources

LangChain4j **1.23.0** (`dev.langchain4j:langchain4j`, `langchain4j-core`) and `langchain4j-mcp`
1.23.0-beta33. Read from their Maven Central sources jars:

- `ToolProvider`, `ToolExecutor` (`executeAsync` default):
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j/src/main/java/dev/langchain4j/service/tool/ToolProvider.java>,
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j/src/main/java/dev/langchain4j/service/tool/ToolExecutor.java>
- Default tool-error handling per AI Service mode:
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j/src/main/java/dev/langchain4j/service/tool/ToolService.java>
- `DefaultToolExecutor`:
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j/src/main/java/dev/langchain4j/service/tool/DefaultToolExecutor.java>
- `ToolSpecification.fromJson`/`toJson` and `JsonSchemaElementJsonUtils`:
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/ToolSpecification.java>,
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j-core/src/main/java/dev/langchain4j/internal/JsonSchemaElementJsonUtils.java>
- `TokenStream`:
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j/src/main/java/dev/langchain4j/service/TokenStream.java>
- Tool search (`ToolSearchStrategy`, `ToolSearchRequest`, `ToolSearchResult`, `ToolSearchService`
  with `found_tools`, `SimpleToolSearchStrategy`), and `SearchBehavior` /
  `ToolSpecification.METADATA_SEARCH_BEHAVIOR`:
  <https://github.com/langchain4j/langchain4j/tree/1.23.0/langchain4j/src/main/java/dev/langchain4j/service/tool/search>,
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/SearchBehavior.java>
- `EmbeddingModel`, `DimensionAwareEmbeddingModel` (`embedAll`/`doEmbed` defaults):
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j-core/src/main/java/dev/langchain4j/model/embedding/EmbeddingModel.java>
- MCP schema mapping precedent:
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j-mcp/src/main/java/dev/langchain4j/mcp/client/ToolSpecificationHelper.java>
