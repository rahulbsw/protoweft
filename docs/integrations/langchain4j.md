# LangChain4j

`protoweft-langchain4j` connects Protoweft to [LangChain4j](https://docs.langchain4j.dev)
**1.23.0** in three directions, without Spring and without depending on any protocol module:

1. **Tools in:** any Protoweft `ToolSource` (MCP, UTCP, A2A, local tools, a `ToolRegistry`) is
   offered to a LangChain4j AI Service as a `ToolProvider`.
2. **Agents out:** a LangChain4j AI Service becomes an `AgentHandler`, so the
   [A2A server](../protocols/a2a.md#serving-an-agent) can serve it.
3. **`@Tool` methods out:** LangChain4j `@Tool` methods become `LocalTools.LocalTool`s, so the MCP
   server and the UTCP provider can serve them.

```kotlin
dependencies {
    implementation(platform("io.github.rahulbsw:protoweft-bom:0.1.0-SNAPSHOT"))
    implementation("io.github.rahulbsw:protoweft-langchain4j")   // brings dev.langchain4j:langchain4j
    implementation("io.github.rahulbsw:protoweft-mcp")            // and whichever protocols you use
}
```

LangChain4j types appear in this module's public API, so it exposes `dev.langchain4j:langchain4j`
as an `api` dependency. Add your model provider (for example `langchain4j-open-ai`) yourself.

## Tools in

```java
ToolRegistry registry = new ToolRegistry()
        .register("weather", mcpConnector)
        .register("local", LocalTools.builder().scan(new MathTools()).build());

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .toolProvider(ProtoweftToolProvider.from(registry))
        .build();
```

`ProtoweftToolProvider.builder(source)` offers more control:

| Method | Default | Meaning |
|---|---|---|
| `dynamic(boolean)` | `false` | List tools before every model call in the tool loop, not only once per AI Service invocation (LangChain4j's `isDynamic`). |
| `filter(predicate)` | all tools | Offer only matching tools, tested against the Protoweft `ToolSpec`. |
| `toolNames(mapper)` | `ToolNames::sanitize` | How Protoweft names become model-facing names; the result must be unique and accepted by your provider. |
| `callerIdentity(supplier)` | anonymous | The `CallerIdentity` passed to `ToolSource.invoke`. |
| `callerIdentityFrom(function)` | anonymous | Derive the caller from LangChain4j's `InvocationContext` (which may be `null`). |
| `asyncExecutor(executor)` | a shared cached pool of daemon threads | Runs blocking tool calls for asynchronous AI Services. |

Tools are listed from the source on every `provideTools` call; caching, if you want it, belongs in
the source.

### Tool names

Model providers commonly restrict tool names to `^[a-zA-Z0-9_-]{1,64}$` (OpenAI's limit), while
registry names look like `weather.forecast`. `ToolNames.sanitize` maps `.` to `__`, other disallowed
characters to `_`, and shortens names over 64 characters to 55 characters plus `_` and an 8-digit
hex hash. So `weather.forecast` is offered as `weather__forecast`.

The mapping isn't reversed by parsing: each executor is bound to its original name, which is also
stored in the tool specification's metadata under `ProtoweftToolProvider.TOOL_NAME_METADATA`
(`protoweft.toolName`). `provider.originalName("weather__forecast")` returns the original name.
Two tools that map to the same name fail `provideTools` with `IllegalStateException` instead of one
silently shadowing the other.

### Schemas

Input schemas are converted through LangChain4j's public JSON API, so nested keywords LangChain4j
has no typed model for (`format`, `minimum`, `oneOf`, …) survive as raw schema. Only the root must
be a plain object schema: a missing `type` means object, a non-object `type` is rejected, draft-07
`definitions` become `$defs`, schema-valued `additionalProperties` becomes `true`, and other root
keywords (`$schema`, `title`, `allOf`, …) are dropped. `JsonSchemas.toJsonObjectSchema(map)` and
`JsonSchemas.toMap(schema)` expose the conversion.

### Results and errors

The model receives text: text parts as they are, JSON parts serialized, links as their URI, and
binary or unmapped parts as visible bracketed markers. When there are no parts, it receives the
structured payload as JSON. The full `ToolOutcome` is attached as `ToolExecutionResult.result()`
for your code; LangChain4j doesn't send it to the model.

Errors follow LangChain4j's own conventions:

| Protoweft result | LangChain4j behavior |
|---|---|
| `ToolOutcome.error() == true` | A `ToolExecutionResult` with `isError` set and the outcome text, sent to the model as an error result, as for a throwing `@Tool`. |
| `ProtoweftException` with `INVALID_ARGUMENTS`, or arguments that aren't a JSON object | `ToolArgumentsException`, handled by the AI Service's `ToolArgumentsErrorHandler` (the default fails a synchronous invocation). |
| Any other `ProtoweftException` | `ToolExecutionException`, handled by the `ToolExecutionErrorHandler` (the synchronous default sends the message to the model; the asynchronous default fails the invocation). |

In both exception cases the handler receives the `ProtoweftException` itself as the cause.

## Agents out

```java
interface Assistant {
    String chat(@MemoryId String conversationId, @UserMessage String message);
}

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
        .build();

AgentHandler handler = LangChain4jAgents.handler(assistant::chat);
AgentDescriptor card = LangChain4jAgents.descriptor("Assistant", "Answers questions", "1.0.0",
        LangChain4jAgents.skill("qa", "Q&A", "General questions", "qa"));

A2aAgentServer server = A2aAgentServer.builder(handler, card).publicUrl("https://agents.example.com").build();
```

- `handler(Conversation)` takes `String chat(String conversationId, String userMessage)`;
  `streamingHandler(StreamingConversation)` takes a method returning a `TokenStream`. An AI Service
  method `chat(@MemoryId String, @UserMessage String)` fits either as a method reference.
- **Memory id.** The conversation id is the request's `contextId`, falling back to its `taskId`.
  LangChain4j chat memory therefore follows A2A contexts across tasks. The request history isn't
  replayed, because chat memory owns the conversation.
- **Results.** The answer becomes one text artifact named `response`
  (`LangChain4jAgents.ARTIFACT_NAME`) and the task completes. An exception fails the task with its
  message.
- **Streaming.** Each partial response becomes one `working` status carrying that fragment (not
  the accumulated text). Callbacks are replayed on the handler's thread. Cancelling stops
  listening, but LangChain4j's stream itself isn't aborted.

## `@Tool` methods out

```java
class MathTools {
    @Tool("Adds two numbers")
    public int add(int a, int b) {
        return a + b;
    }
}

LocalTools tools = LocalTools.builder()
        .addAll(LangChain4jTools.from(new MathTools()))
        .build();

McpToolServer.http(tools);   // or UtcpManualProvider.builder(tools).build()
```

Specifications come from LangChain4j's `ToolSpecifications`, and calls run through its
`DefaultToolExecutor`, so argument binding and result formatting are exactly LangChain4j's. An
exception from the method becomes a failure outcome; an argument-binding error becomes
`ProtoweftException(INVALID_ARGUMENTS)`. The Protoweft caller reaches the method through
LangChain4j's `InvocationParameters`, under `LangChain4jTools.CALLER_PARAMETER`
(`protoweft.caller`).

## Limitations

- Binary tool content (images) isn't mapped into LangChain4j's multimodal tool results.
- Agents built with `LangChain4jAgents` don't produce `input-required` or `auth-required` states,
  and tool executions aren't reported as task status.
- `LangChain4jTools` produces text and image content only, with no structured payload, and `@Tool`
  names must already match `[A-Za-z0-9_-]{1,64}`.
- Root-level schema keywords beyond LangChain4j's `JsonObjectSchema` model are dropped.
