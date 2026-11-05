---
id: agentweave-langchain4j
type: module-design
status: draft
title: agentweave-langchain4j — LangChain4j adapter (tools in, agents out, @Tool out)
parent: agentweave-architecture
---

# agentweave-langchain4j

## Responsibility

Shows that AgentWeave plugs into a non-Spring agent framework, in three directions, against
**LangChain4j 1.23.0**:

1. **Tools in** — any AgentWeave `ToolSource` (MCP, UTCP, A2A, local tools, a `ToolRegistry`) is
   offered to a LangChain4j AI Service as a `ToolProvider`.
2. **Agents out** — a LangChain4j agent (an AI Service method) becomes a core `AgentHandler`, so
   an AgentWeave agent server (for example the A2A server) can serve it.
3. **@Tool out** — LangChain4j `@Tool` methods become `LocalTools.LocalTool`s, so AgentWeave tool
   servers (MCP server, UTCP manual) can serve them.

## Boundary

- Depends on `agentweave-core` and `dev.langchain4j:langchain4j` only (`api` scope: LangChain4j
  types appear in the public API). Jackson 2 is used internally, unversioned, so the version
  LangChain4j brings governs. No dependency on any protocol module.
- Public API (package `io.agentweave.langchain4j`):
  - `AgentWeaveToolProvider implements ToolProvider` — `from(ToolSource)`,
    `builder(ToolSource)` with `dynamic`, `filter`, `toolNames`, `callerIdentity`,
    `callerIdentityFrom`, `asyncExecutor`; `originalName(String)`; `TOOL_NAME_METADATA`.
  - `LangChain4jAgents` — `handler(Conversation)`, `streamingHandler(StreamingConversation)`,
    `descriptor(...)`, `skill(...)`, `ARTIFACT_NAME`. `Conversation` is
    `String chat(String conversationId, String userMessage)`; `StreamingConversation` returns a
    `TokenStream`. An AI Service method `chat(@MemoryId String, @UserMessage String)` fits either
    as a method reference.
  - `LangChain4jTools.from(Object...)` → `List<LocalTools.LocalTool>`; `CALLER_PARAMETER`.
  - `JsonSchemas.toJsonObjectSchema(Map)` / `toMap(JsonObjectSchema)`; `ToolNames.sanitize`.

Serving an agent over A2A (the server lives in `agentweave-a2a`):

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
  in the specification's metadata (`agentweave.toolName`; LangChain4j does not send metadata to
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
  - An `AgentWeaveException` is not turned into text. It is rethrown wrapped, so the AI Service's
    configured `ToolArgumentsErrorHandler`/`ToolExecutionErrorHandler` decides what happens, and
    the handler receives the `AgentWeaveException` itself. `INVALID_ARGUMENTS` (and arguments
    that aren't a JSON object) become `ToolArgumentsException`; the default handler fails a sync
    invocation. Other categories become `ToolExecutionException`; the sync default sends the
    message to the model, and the async default fails the invocation.
  - `LangChain4jTools` runs `@Tool` methods through `DefaultToolExecutor`. An exception from
    the method becomes a failure outcome. An argument-binding error that escapes it becomes
    `AgentWeaveException(INVALID_ARGUMENTS)`, as `LocalTools.scan` does.
- **Result text.** The model gets text: text parts as-is, JSON parts serialized, links as their URI,
  and binary or unmapped parts as visible bracketed markers. They're never dropped silently. When
  there are no parts, it gets the structured payload as JSON. The full `ToolOutcome` is attached as
  `ToolExecutionResult.result()` for application code, and LangChain4j doesn't send it to the model.
- **Async.** `executeAsync` is overridden, since LangChain4j's default fails asynchronous AI
  Services. The blocking call runs on a configurable `Executor`. The default is a shared cached
  pool of daemon threads, so blocking network calls never occupy the common pool.
- **Caller identity.** On the way in, a `Function<InvocationContext, CallerIdentity>` (or a
  supplier) chooses the caller passed to `ToolSource.invoke`. The default is anonymous. On the way
  out, the AgentWeave caller reaches a `@Tool` method as `InvocationParameters` entry
  `agentweave.caller`.
- **Memory id.** The conversation id is the request's `contextId`, falling back to the `taskId`
  (then a random id). It's the protocol's conversation, so LangChain4j chat memory follows A2A
  contexts across tasks. The request's `history` isn't replayed, because chat memory owns the
  conversation.
- **Agent results.** The answer becomes one text artifact (`response`), then the task is
  completed. An exception fails the task with its message. While streaming, each partial
  response becomes one `working` status carrying that fragment, not the accumulated text. Stream
  callbacks are queued and replayed on the handler's thread, so `AgentTaskContext` is only called
  from one thread. Cancellation stops listening, but LangChain4j's stream isn't aborted.

## Coverage and limitations

- Not mapped: binary tool content (images) into LangChain4j's multimodal tool results
  (`resultContents` is `@Experimental` and provider-dependent). `requireInput`/`requireAuth`
  flows aren't produced. Tool-execution events aren't reported as task status.
- `LangChain4jTools` gives text/image content only, with no structured payload. `@Tool` names
  must already match `[A-Za-z0-9_-]{1,64}`.
- Root-level schema keywords beyond `JsonObjectSchema`'s model are dropped (see above).

## Verification

`./gradlew :agentweave-langchain4j:build` runs JUnit tests with no network and no real LLM:

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
- MCP schema mapping precedent:
  <https://github.com/langchain4j/langchain4j/blob/1.23.0/langchain4j-mcp/src/main/java/dev/langchain4j/mcp/client/ToolSpecificationHelper.java>
