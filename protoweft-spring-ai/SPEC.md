---
id: protoweft-spring-ai
type: module-design
status: draft
title: protoweft-spring-ai — Spring AI adapter (tools in, tool search, ChatClient agents, @Tool out)
parent: protoweft-architecture
---

# protoweft-spring-ai

## Responsibility

Plugs Protoweft into **Spring AI 2.0.2** in four directions:

1. **Tools in.** Any Protoweft `ToolSource` (MCP, UTCP, A2A, local tools, a `ToolRegistry`) is
   offered to a `ChatClient` as a `ToolCallbackProvider`.
2. **Tool search.** A `CapabilityCatalog` is Spring AI's `ToolIndex`, used by
   `ToolSearchToolCallingAdvisor`. The model starts with `toolSearchTool` only and receives full
   definitions just for the capabilities the catalog finds for the caller.
3. **Agents out.** A `ChatClient` becomes a core `AgentHandler`, blocking or streaming, so an
   Protoweft agent server (for example A2A) serves it with the full task lifecycle.
4. **Tools out.** Spring AI `ToolCallback`s, including `@Tool` methods through
   `MethodToolCallbackProvider`, become `LocalTools.LocalTool`s, so Protoweft's MCP and UTCP servers
   can serve them.

## Boundary

- **Dependencies.**
  - `api`: `protoweft-core` and `spring-ai-model`. `ToolCallback`, `ToolCallbackProvider`,
    `ToolDefinition` and `ToolContext` appear in the public API.
  - `implementation`: Jackson 3, unversioned, so Spring AI's version governs.
  - `compileOnly`: `spring-ai-client-chat` (`SpringAiAgents`), `spring-ai-tool-search-tool` and
    `protoweft-discovery` (`CatalogToolIndex`), and `spring-ai-tool-search-advisor` (Javadoc
    only). An application adds them when it uses those classes. `ProtoweftToolCallbacks` and
    `SpringAiTools` need only `spring-ai-model`.
  - No dependency on any protocol module.
- **Public API** (package `io.github.rahulbsw.protoweft.springai`):
  - `ProtoweftToolCallbacks implements ToolCallbackProvider`:
    - `from(ToolSource)` and `builder(ToolSource)`, with `dynamic`, `filter`, `toolNames`,
      `callerIdentity` and `callerIdentityFrom(Function<ToolContext, CallerIdentity>)`;
    - `refresh()`, `originalName(String)`;
    - constants `CALLER_KEY` (`protoweft.caller`) and `PROGRESS_KEY` (`protoweft.progress`).
  - `CatalogToolIndex implements ToolIndex`:
    - `from(CapabilityCatalog)` and `builder(...)`, with `toolNames`, `callerIdentity`,
      `callerIdentityFrom(Function<String sessionId, CallerIdentity>)`, `limit`, `queryCustomizer`
      and `onResult`;
    - `toolCallbacks()`, the matching `ProtoweftToolCallbacks`;
    - constant `SEARCH_TYPE`.
  - `SpringAiAgents`:
    - `handler(ChatClient[, customizer])` and `streamingHandler(ChatClient[, customizer])`;
    - `descriptor(...)`, `skill(...)`, `ARTIFACT_NAME` (`response`).
  - `SpringAiTools`: `from(ToolCallback...)`, `from(ToolCallbackProvider...)`, `CALLER_KEY`,
    `PROGRESS_KEY`.
  - `ToolNames.sanitize`, `ToolNames.PROVIDER_SAFE`.
  - `ToolOutcomeException`, the cause carried for a tool-reported failure.

```java
CatalogToolIndex index = CatalogToolIndex.builder(catalog)
        .callerIdentityFrom(conversationId -> callerOf(conversationId)).build();
ChatClient chat = ChatClient.builder(model)
        .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).build())
        .defaultTools(index.toolCallbacks())
        .build();
chat.prompt().user(q).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)).call().content();
```

## Decisions

- **Tool names.**
  - **Algorithm.** `ToolNames.sanitize` uses the same algorithm as
    `discovery.ProviderToolNames`: `.` becomes `__`, other disallowed characters become `_`, and
    names over 64 characters are cut to 55 plus `_` and 8 hex digits of SHA-256. A catalog
    capability therefore gets the same provider name here as in Protoweft's own provider
    payloads. The algorithm is copied rather than calling `ProviderToolNames`, because discovery
    is optional here.
  - **No parsing.** Names are never parsed back. Each callback is bound to its original name, and
    the latest listing's map answers `originalName`.
  - **Clashes.** Two tools mapping to one name fail the listing with `IllegalStateException`.
  - **Shared mapper.** `CatalogToolIndex` and its `toolCallbacks()` share one mapper, so every
    search hit names a registered callback.
- **Listing.**
  - **Default.** By default (`dynamic(true)`) the source is listed on every `getToolCallbacks()`,
    which Spring AI calls once per `ChatClient` request, as the LangChain4j adapter does per
    invocation. Caching belongs in the source.
  - **Snapshot.** `dynamic(false)` lists once and keeps the result until `refresh()`.
  - **Schemas.** They are serialized with Jackson 3. An empty schema, or one without `type`,
    becomes an object schema, because providers require one. An empty description falls back to
    Spring AI's name-derived description.
- **Errors go through Spring AI's processor.** Both a tool-reported failure (`ToolOutcome.error()`,
  carried as `ToolOutcomeException` with the outcome) and an `ProtoweftException` are thrown as
  `ToolExecutionException(definition, cause)`. Arguments that aren't a JSON object are thrown the
  same way, with an `ProtoweftException(INVALID_ARGUMENTS)` cause.
  - **Why.** Spring AI turns only `ToolExecutionException` into text for the model. Any other
    exception ends the call. Throwing it lets the configured `ToolExecutionExceptionProcessor`
    decide.
  - **Default.** The default processor sends the message to the model.
  - **Rethrowing.** `alwaysThrow` or `rethrowExceptions(ProtoweftException.class)` make
    failures end the call instead.
- **Result text.** Text parts are kept as-is, JSON parts are serialized, links become their URI,
  a text `Resource` becomes its URI followed by its text, and binary or unmapped parts become
  visible bracketed markers. With no parts, the model gets
  the structured payload as JSON. This is the same rendering as the LangChain4j adapter.
- **Caller identity.**
  - **Tools in.** `call(input, ToolContext)` is overridden, because Spring AI's default ignores
    the context. The caller is `ToolContext["protoweft.caller"]` (a `CallerIdentity`), or a
    configured function of the context, or anonymous. A `ToolProgress` under
    `protoweft.progress` is forwarded to `ToolSource.invoke(inv, caller, progress)`.
  - **Tools out.** `SpringAiTools` puts the Protoweft caller and progress into the same two keys,
    which a `@Tool` method reads by declaring a `ToolContext` parameter.
- **Tool search.**
  - **The catalog is the index.** `indexTool`, `indexTools` and `clearIndex` do nothing.
    `search` builds a `DiscoveryQuery`:
    - The caller is the session-id function applied to the advisor's session id, by default the
      request's `ChatMemory.CONVERSATION_ID`.
    - The limit is `maxResults`, else the builder's `limit` (5).
    - `categoryFilter` is read as comma-separated tokens. Tokens naming a catalog source become
      `sources`, tokens naming a protocol id become `protocols`, and other tokens are ignored, so
      a category the model guesses never empties the result.
    - A `queryCustomizer` can tighten the query.
  - **Permissions and availability.** The catalog's permission filter, availability filter,
    ranking and fallback apply. Matches hidden by permission are never named.
  - **Fallback stages.** Hits at every stage are returned (`ALL_SCHEMAS` returns every permitted
    capability, which reproduces the all-tools baseline). `NAMES_ONLY` candidates are not, because
    the advisor loads the full definition of every name returned, and there is no text-only
    channel.
  - **Metadata.** `SearchMetadata` has fields only for the type, query and time, and the model
    sees only the list of names. Confidence, stage and hidden counts therefore go to an
    `onResult(BiConsumer<ToolSearchRequest, DiscoveryResult>)` listener rather than being encoded
    into `searchType`.
  - **Callbacks.** `toolCallbacks()` covers every capability. Schemas come from the catalog's
    `SchemaCache`, and calls go through `CapabilityCatalog.invoke`, which re-checks permission and
    availability. The caller is `protoweft.caller` if it's in the tool context, else the
    session-id function applied to the advisor's `toolSearchToolSessionId` tool-context entry. The
    search and the call therefore see the same caller.
- **Agents.**
  - **Why Protoweft serves `ChatClient`s over A2A.** Spring AI has no A2A in core. The community
    `spring-ai-a2a-server` 0.4.0 (on a2a-java 1.4.0) is one-shot: one string, one artifact, no
    streaming and no `input-required`. `SpringAiAgents` runs on Protoweft's full-lifecycle
    `A2aAgentServer` instead.
  - **Request.** Each message is one `ChatClient` request: `user(text)`, advisor parameter
    `ChatMemory.CONVERSATION_ID` = `contextId` (falling back to the taskId, then a random id), and
    tool context `protoweft.caller` = `request.caller()`. An optional
    `BiConsumer<AgentRequest, ChatClientRequestSpec>` customizer runs last.
  - **Memory.** Memory takes effect only if the client has a memory advisor. The A2A history isn't
    replayed, because the memory owns the conversation.
  - **Results.** The answer becomes one text artifact `response`, then the task is completed. An
    exception, or a message with no text (Spring AI rejects blank user text), fails the task.
  - **Streaming.** `stream().chatResponse()` chunks are sent with `textDelta`, one call each, and no
    final artifact is added: the fragments are the answer. Events are queued and handled on the
    handler's thread, so `AgentTaskContext` is called from one thread.
  - **Tool calls reported.** The tool context also carries `protoweft.task` (the
    `AgentTaskContext`); `ProtoweftToolCallback` reports each call with `toolCall` before and
    `toolResult` after (failures included). Spring AI's tool-calling advisor is not replaced, so
    its configuration survives; only Protoweft tools are reported.
  - **Over AG-UI** (decided with the user; `AgUiPrompts`, the only class touching
    `protoweft-agui`, which is `compileOnly` and loaded only after a class-presence check):
    - The prompt is the run's own conversation (`messages(...)`, not `user(text)`): system and
      developer → system, user text (media parts not sent), assistant text with tool calls,
      consecutive tool messages → one `ToolResponseMessage`; activity and reasoning skipped; the
      run's context entries as a trailing system message. Chat memory is not the source of
      history; a memory advisor on the AG-UI `ChatClient` would duplicate it (documented).
    - A boolean tool schema becomes its object equivalent (`false`: no properties and no
      additional ones; `true`: an unconstrained object), because providers take object schemas.
    - The application's tools become per-request `returnDirect` callbacks (`tools(...)`, the
      non-deprecated form) whose call emits the AG-UI tool call and returns `""`; Spring AI then
      ends the turn, and that placeholder generation (finish reason `returnDirect`, `toolName` one
      of them) is dropped from the text. The next run carries the result as a tool message.
    - Limitation: a step calling an application tool together with an agent-side tool lets Spring
      AI continue with the agent-side result; the application call stays pending.
  - **Cancellation.** It disposes the subscription, which cancels the upstream `Flux`. This is
    different from LangChain4j, whose stream couldn't be aborted.
- **Tools out.**
  - **Calls.** Each callback is called with the arguments as JSON.
  - **Results.** A JSON string result is unquoted (Spring AI's default converter JSON-encodes
    strings). A JSON object or array result is the text and also the structured payload. Anything
    else is text.
  - **Errors.** A `ToolExecutionException` is a failure outcome with its message, unless its cause
    is an `ProtoweftException`, which propagates. Other exceptions are reported as failures by
    `LocalTools`.
  - **Names.** Tool names must match `[A-Za-z0-9_-]{1,64}`. Duplicates are rejected.
    `returnDirect` is kept as metadata `springAi.returnDirect`.
- **Not a bean by default.** Spring AI's MCP server auto-configuration re-exports every
  `ToolCallbackProvider` bean, so registering `ProtoweftToolCallbacks` as a bean is left to the
  application (or an opt-in starter property).

## Coverage and limitations

- **Tool content.** Binary tool content isn't forwarded to the model, only as a marker.
  `SpringAiTools` produces text and structured payloads only.
- **Agent flows.** `requireInput` and `requireAuth` flows aren't produced. Tool-execution events
  aren't reported as task status.
- **Tool search.**
  - Only catalog capabilities are searchable. Other callbacks on the same request are indexed by
    the advisor into a no-op index and are never found. Put them into the catalog, for example as a
    `LocalTools` source.
  - The caller for search is derived only from the session id, since `ToolSearchRequest` carries
    nothing else.
  - `toolCallbacks()` serializes every catalog schema per request, which costs CPU (not tokens) on
    very large catalogs.
  - The advisor shows the model the names a search returned, even names with no registered
    callback. It loads only the registered ones.
- **Ordering.** Spring AI allows one `ToolAdvisor` per `ChatClient`. The search advisor replaces
  the default tool-calling advisor.

## Verification

`./gradlew :protoweft-spring-ai:build` runs 29 JUnit tests with no network and no real LLM. A
scripted `ChatModel` returns tool-call `AssistantMessage`s, then text, and drives a real
`ChatClient`.

- **`ProtoweftToolCallbacksTest` (9).**
  - A registry tool is called with parsed arguments and the caller from the tool context.
  - An error outcome, an `ProtoweftException` and invalid JSON reach the model as messages.
  - Exception causes are checked.
  - Structured results become JSON.
  - Empty schemas are normalized.
  - The caller function works.
  - Names map back, and clashes fail.
  - Dynamic listing is compared with a snapshot.
  - Long names match `ProviderToolNames`.
  - Every content part is rendered.
- **`CatalogToolIndexTest` (5), with the real `ToolSearchToolCallingAdvisor`.**
  - The first request offers only `toolSearchTool`. After a search, the found tool's definition is
    offered, and the call runs.
  - A scoped tool never appears for an anonymous session.
  - A session mapped to a caller with the scope finds the tool and calls it as that caller.
  - Request mapping covers `maxResults`, category tokens and metadata.
  - Indexing is a no-op, and the customizer applies.
- **`SpringAiAgentsTest` (9).**
  - Blocking: the artifact is produced and the task completes. Memory follows `contextId`, and
    falls back to the taskId.
  - The caller reaches tools, both blocking and streaming.
  - The customizer applies.
  - Failures, including blank text, fail the task.
  - Streaming: chunks become `textDelta` calls on the handler thread.
  - A stream error fails the task.
  - Cancellation disposes the upstream.
- **`SpringAiToolsTest` (6).**
  - `@Tool` beans go through `MethodToolCallbackProvider` and become `LocalTools`.
  - Schemas are checked.
  - Arguments, results (including structured), caller and progress round-trip through
    `LocalTools.invoke`.
  - Failures and `ProtoweftException` are handled as described above.
  - Invalid and duplicate names are rejected.
  - Tools are served back to a `ChatClient` through `ProtoweftToolCallbacks`.

`examples/spring-ai-example` (3 tests) runs the Boot application and talks to it over A2A with a
scripted `@Primary` `ChatModel`. It checks a tool-using blocking answer, a streamed answer with
`working` fragments, and memory per context.

## Sources

Spring AI **2.0.2** (Maven Central sources jars of `spring-ai-model`, `spring-ai-client-chat`,
`spring-ai-tool-search-tool`, `spring-ai-tool-search-advisor`, `spring-ai-commons`,
`spring-ai-openai` and `spring-ai-autoconfigure-model-openai`):

- `org/springframework/ai/tool/ToolCallback.java`: the default `call(input, ToolContext)` ignores
  the context. `ToolCallbackProvider.java`, `definition/DefaultToolDefinition.java` (blank
  description is derived from the name), `chat/model/ToolContext.java`.
- `tool/execution/ToolExecutionException.java` (message = cause's message) and
  `DefaultToolExecutionExceptionProcessor.java` (returns the message; `alwaysThrow`,
  `rethrowExceptions`).
- `model/tool/DefaultToolCallingManager.java`: only `ToolExecutionException` is caught, and the
  tool context comes from `ToolCallingChatOptions`.
- `tool/method/MethodToolCallback.java` (method exceptions become `ToolExecutionException`) and
  `tool/execution/DefaultToolCallResultConverter.java` (JSON-encodes results).
- `chat/memory/ChatMemory.java` (`CONVERSATION_ID`).
- `chat/client/ChatClient.java`, `DefaultChatClient.java` (auto-registers `ToolCallingAdvisor`,
  allows one `ToolAdvisor`), `DefaultChatClientUtils.java` (providers are resolved per request;
  advisor params become the request context).
- `tool/toolsearch/ToolIndex.java`, `ToolSearchRequest.java`, `ToolReference.java`,
  `ToolSearchResponse.java` (`SearchMetadata`), `ToolSearchTool.java` (returns only names;
  `toolSearchToolSessionId`).
- `chat/client/advisor/toolsearch/ToolSearchToolCallingAdvisor.java`: it indexes every request
  callback, offers `toolSearchTool` plus the found names, and takes the session from
  `ChatMemory.CONVERSATION_ID`.
- `model/openai/autoconfigure/OpenAiChatAutoConfiguration.java` and `openai/setup/OpenAiSetup.java`:
  an empty `spring.ai.openai.api-key` means no-auth mode, so the example starts without a key.
