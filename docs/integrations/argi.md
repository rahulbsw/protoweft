# ARGI

[ARGI](https://github.com/agentic-ai-java/argi) is a Java agent framework (`io.github.agentic-ai-java`).
AgentWeave has two adapters for it, one per ARGI API generation:

| Module | ARGI version | API | Published |
|---|---|---|---|
| `agentweave-argi` | `2.1.0-RC1`, from Maven Central | Spring AI based (`io.github.agentic.ai.graph.*`, `ReactAgent`) | yes, with the other modules |
| `agentweave-argi-neutral` | `2.1.0-RC2-SNAPSHOT`, a pinned commit of ARGI `main` | Spring-free neutral API (`argi-api`, `argi-agent-api`, `ReActAgent`) | **no**: built from source only, see [below](#agentweave-argi-neutral-unpublished) |

Both connect AgentWeave and ARGI in the same directions: AgentWeave tools in, catalog-based tool
selection without an extra model call, and ARGI agents served over A2A with human approvals carried
as `input-required`.

## `agentweave-argi` (ARGI 2.1.0-RC1)

```kotlin
dependencies {
    implementation(platform("io.agentweave:agentweave-bom:0.1.0-SNAPSHOT"))
    implementation("io.agentweave:agentweave-argi")        // brings argi-agent-framework 2.1.0-RC1 and agentweave-spring-ai
    implementation("io.agentweave:agentweave-discovery")   // only for CatalogToolSelectionInterceptor
    implementation("io.agentweave:agentweave-a2a")         // to serve agents over A2A
}
```

ARGI RC1 is built on Spring AI and takes Spring AI `ToolCallback`s, so this module reuses the
[Spring AI adapter](spring-ai.md) for every callback: tool names, schemas, error handling and result
rendering are identical (`weather.forecast` becomes `weather__forecast`). It pins
`spring-ai-client-chat` 2.0.2 so ARGI's Spring AI 2.0.1 lines up with the `spring-ai-model` 2.0.2
that `agentweave-spring-ai` exports. ARGI also brings Jackson 2 and its own older A2A client
(`io.github.a2asdk` 0.3.3); those packages differ from the a2a-java SDK that `agentweave-a2a` uses,
so both can be on the classpath.

!!! warning "Release candidate"
    This module targets the published RC1 only. ARGI `main` has moved to the Spring-free API that
    `agentweave-argi-neutral` covers, and package names, `ModelRequest`, `InterruptionMetadata` and
    the human-in-the-loop resume pattern may change in later releases.

### Tools in

```java
ReactAgent agent = ReactAgent.builder()
        .name("assistant")
        .model(chatModel)
        .toolCallbackProviders(ArgiTools.callbackProvider(registry))   // weather.forecast -> weather__forecast
        .saver(new MemorySaver())
        .build();

agent.call("Weather in Paris?",
        ArgiTools.withCaller(RunnableConfig.builder().threadId("thread-1"), caller).build());
```

`ArgiTools.callbackProvider(source)` is an `AgentWeaveToolCallbacks`; use
`AgentWeaveToolCallbacks.builder(source)` directly for filters or other names.

**The caller travels in the run's metadata.** ARGI builds each tool's `ToolContext` from the agent's
static `toolContext(Map)` plus the run's `RunnableConfig` metadata, so a `CallerIdentity` under
`agentweave.caller` (`ArgiTools.CALLER_KEY`) reaches the tool either way:

- per run, with `ArgiTools.withCaller(configBuilder, caller)`, which `ArgiAgents` does for every A2A
  request;
- fixed, with `.toolContext(Map.of(ArgiTools.CALLER_KEY, caller))` on the agent builder.

Without either, tools are called anonymously. `ArgiTools.callerFrom(map)` reads the caller from an
ARGI context map.

**Tools are a snapshot.** ARGI calls `getToolCallbacks()` once, when the agent is built. Tools a
source gains later need a new agent, and dynamic listing doesn't apply.

### Catalog tool selection

ARGI's own `ToolSelectionInterceptor` asks a selection model to pick tools from names and
descriptions, which costs an extra LLM call per model call. `CatalogToolSelectionInterceptor` asks
the [capability catalog](../discovery/index.md) instead:

```java
ReactAgent agent = ReactAgent.builder()
        .name("assistant").model(chatModel)
        .interceptors(CatalogToolSelectionInterceptor.builder(catalog).limit(5).build())
        .saver(new MemorySaver())
        .build();   // the interceptor registers the catalog's tools itself
```

| | ARGI `ToolSelectionInterceptor` | `CatalogToolSelectionInterceptor` |
|---|---|---|
| How it selects | Asks a selection `ChatModel` to pick from names and descriptions | Ranks with the catalog index (keyword, or hybrid with an embedder) |
| Cost per model call | One extra LLM call and its latency | A catalog search, no model call |
| Permissions and availability | Not considered | Filtered by the caller's scopes and the source's availability; hidden tools are never offered |
| On failure | Falls back to all tools | `whenNothingFound` policy (default: every catalog tool the caller may see) |
| Observability | — | `onResult` receives confidence, fallback stage and hidden counts |

How it works, on **every** model call, including those after tool results:

- **Query:** the text of the latest user message, optionally preceded by up to `contextMessages`
  earlier user or assistant texts, searched for the caller in the request context (set by
  `ArgiAgents`, or `ArgiTools.withCaller`).
- **Offered tools:** the catalog hits, plus `alwaysVisible` names, plus (by default) the agent's
  tools that aren't catalog capabilities, such as hook or interceptor tools, which the catalog
  can't rank. Hits at every fallback stage are offered (`BROADENED`, and `ALL_SCHEMAS`, which is
  every permitted capability); `NAMES_ONLY` candidates are not.
- **Nothing found** (also when the search throws or there's no user text): `KEEP_ALL` (default)
  offers every catalog tool the caller may see whose source is available; `KEEP_NONE` offers no
  catalog tools. Since ARGI reads an empty tool list as "all tools", "no tools" is sent as a single
  name that matches no callback.
- **Registration:** `getTools()` returns callbacks for every catalog capability, so ARGI registers
  them when the agent is built. Calls go through `CapabilityCatalog.invoke`, which re-checks
  permission and availability, so a guessed name of a hidden tool still fails.

| Builder method | Default | Meaning |
|---|---|---|
| `limit(n)` | 5 | Hits per search, before any fallback broadening. |
| `contextMessages(n)` | 0 | Earlier user or assistant texts added to the query. |
| `alwaysVisible(names...)` | none | Provider tool names (for example `local__help`) offered on every call. |
| `keepUncataloguedTools(boolean)` | `true` | Offer the agent's tools that aren't catalog capabilities. |
| `whenNothingFound(NoMatch)` | `KEEP_ALL` | `KEEP_ALL` or `KEEP_NONE`, as above. |
| `registerCatalogTools(boolean)` | `true` | Let ARGI register the catalog's callbacks; turn off to register `toolCallbacks()` yourself. |
| `toolNames(mapper)` | `ToolNames::sanitize` | Names for both the hits and the callbacks. |
| `callerIdentity(supplier)`, `callerIdentityFrom(function)` | `agentweave.caller` from the request context | Where the caller comes from. |
| `queryCustomizer((request, query) -> ...)` | none | Tighten every query, for example `query.exclude(CapabilityHint.DESTRUCTIVE)`. |
| `onResult((request, result) -> ...)` | none | Observe every catalog result. |

### Agents over A2A, with approvals

`ArgiAgents` turns an ARGI `Agent` (typically a `ReactAgent`) into an `AgentHandler` for the
[A2A server](../protocols/a2a.md#serving-an-agent). ARGI's `HumanInTheLoopHook` pauses before a
sensitive tool; the pause becomes A2A `input-required`, and the caller approves, rejects or edits
the call by replying on the same task.

```java
ReactAgent agent = ReactAgent.builder()
        .name("payments").model(chatModel)
        .toolCallbackProviders(ArgiTools.callbackProvider(registry))
        .hooks(HumanInTheLoopHook.builder().approvalOn("bank__transfer", "Moves money").build())
        .saver(new MemorySaver())                 // required: paused runs resume from it
        .throwOnModelError(true)                  // a model error fails the task
        .build();

AgentHandler handler = ArgiAgents.handler(agent);   // or ArgiAgents.streamingHandler(agent)
A2aAgentServer server = A2aAgentServer.builder(handler, card).build();
```

`HumanInTheLoopHook.approvalOn` takes the provider tool names (`bank__transfer`, not
`bank.transfer`).

- **Runs.** Each message is one ARGI run with the message text as the user message and the A2A
  caller in the run's metadata. The thread id is the A2A task id, so a paused run resumes on the
  same thread; `ArgiAgents.builder(agent).threadId(r -> r.contextId())` keeps one ARGI conversation
  per A2A context instead. `config((request, configBuilder) -> ...)` adjusts every run's
  `RunnableConfig`.
- **Answers.** The agent's `outputKey` value, else the last assistant message, becomes an artifact
  named `response` (`ArgiAgents.ARTIFACT_NAME`), and the task completes. Exceptions fail the task
  with the root cause's message.
- **Model errors.** ARGI's default `throwOnModelError(false)` turns a model error into an
  `"Exception: ..."` assistant answer, which completes the task. Set `throwOnModelError(true)` to
  fail it instead.
- **Streaming.** `streamingHandler` (or `builder(agent).streaming(true)`) uses `Agent.stream`, and
  model text chunks become `working` statuses. Cancelling the task disposes the subscription.
- **A saver is required.** Building a handler for an agent without a checkpoint saver fails with
  `IllegalArgumentException` naming `MemorySaver`.

**The question.** When a run pauses with pending tool approvals, the task becomes `input-required`
with a text question listing each pending call: tool name, tool-call id, arguments and the hook's
description, followed by how to answer.

**The reply** is another message on the same task (with its `taskId` and `contextId`):

| Reply | Effect |
|---|---|
| Text `approve`, `approved`, `yes`, `y`, `ok` or `allow` | Approves every undecided call. |
| Text `reject <reason>` (or `rejected`, `no`, `n`, `deny`, `denied`) | Rejects every undecided call; the model is told the reason (a default text without one). |
| A JSON data part `{"decision": "approved", "id": "<tool call id>"}`, with `"rejected"` (and an optional `"reason"`) or `"edited"` (with `"arguments": {...}`) as the decision | Decides the call with that tool-call `id`. `"name": "<tool>"` instead selects by tool name when that name is unique. Without either, it decides every undecided call; an `edited` decision then needs exactly one. |
| A list of such objects, or `{"decisions": [...]}` | Decides calls one by one. |

The run resumes only when every pending call is decided. Until then the handler keeps the partial
decisions and asks again for the rest; an unparsable reply also asks again, stating the problem.
(ARGI itself re-interrupts for every call when feedback is partial.)

```bash
# Approve the pending transfer on task <taskId>
curl -s -X POST localhost:8080/a2a -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{"message":{"messageId":"m2","role":"ROLE_USER","taskId":"<taskId>","contextId":"<contextId>","parts":[{"text":"approve"}]}}}'

# Or edit its arguments with a data part
#   "parts":[{"data":{"decision":"edited","id":"<tool call id>","arguments":{"to":"bob","amount":10}}}]
```

Interrupts without tool feedback (for example ARGI's `interruptBefore`) fail the task, because
there is nothing to approve. A reply on a task the handler has no paused run for (for example after
a restart) fails with a clear message rather than running the pending calls.

### Limitations

- **Paused runs live in memory.** The pending calls are kept in the handler, the checkpoint in the
  agent's saver. After a restart a persistent saver may still hold the checkpoint, but the handler
  no longer knows the pending calls, so the resumed task fails. A task that's never answered keeps
  its entry until it is cancelled.
- **Text-only question.** The `input-required` question is text, because the core
  `requireInput` has no data parts.
- Only tool-approval interrupts are mapped. Streaming reports model text chunks only; tool
  execution isn't reported as task status.
- Tool lists are fixed when the agent is built (see [Tools in](#tools-in)).

### Example

[`examples/argi-example`](../examples.md#argi) is a Spring Boot application with a `ReactAgent` whose
tools are chosen per request by the starter's catalog through `CatalogToolSelectionInterceptor`,
with a transfer tool that needs approval, served over A2A by `ArgiAgents.handler`. Its test uses a
scripted `ChatModel`, so it needs no API key.

## `agentweave-argi-neutral` (unpublished)

`agentweave-argi-neutral` targets ARGI's Spring-free neutral API (`argi-api`, `argi-agent-api`),
which exists only on ARGI `main` as `2.1.0-RC2-SNAPSHOT`. Maven Central has only the Spring-based
RC1, so this module:

- is built against a **pinned ARGI commit**,
  `a869538fcee17230bc8f20f6ed76c0d15926220e` (recorded in `scripts/install-argi-snapshot.sh`);
- is **not in the default build**: `settings.gradle.kts` includes it only with `-PargiSnapshot`;
- is **not published**, not even by `publishToMavenLocal`, and the BOM excludes it, because a
  published artifact can't depend on an unpublished snapshot. It will be published once ARGI
  publishes 2.1.0-RC2.

### Build it

```bash
scripts/install-argi-snapshot.sh                         # clone, check out the pinned SHA, mvn install
./gradlew -PargiSnapshot :agentweave-argi-neutral:build
```

The script clones ARGI into `~/.cache/agentweave/argi-src` (`ARGI_SRC_DIR`), checks out the pinned
commit, and installs only the Spring-free modules (`argi-api`, `argi-runtime-api`,
`argi-runtime-core`, `argi-agent-api`, `argi-agent-core`) into your local Maven repository
(`MAVEN_REPO_LOCAL`, default `~/.m2/repository`). It needs network access to GitHub and runs ARGI's
Maven wrapper. It is idempotent: a marker file records the installed commit, and `ARGI_FORCE=1`
rebuilds. The module resolves the ARGI group only from `mavenLocal()`, and nothing else from there.
CI runs the same two commands in a separate job.

### What it provides

Package `io.agentweave.argi.neutral`:

| Type | Does |
|---|---|
| `ArgiNeutralTools` | `registry(source)` turns any AgentWeave `ToolSource` into an ARGI `ToolRegistry` of `Tool`s (names via `ToolNames.sanitize`; `builder(source)` adds `filter`, `toolNames`, `callerIdentity`, `callerIdentityFrom`, `executor`, and ends in `build()`, `tools()` or `dynamic()` for `ReActAgent.Builder.dynamicTools`). `toLocalTools(...)` turns ARGI `Tool`s into `LocalTools.LocalTool`s, so AgentWeave's MCP and UTCP servers can serve them. |
| `CatalogToolSelectionInterceptor` | A `ModelInterceptor` that searches the catalog for the last user message on each model turn and shows the model only the hits, plus tools that aren't catalog capabilities (`keepOtherTools`) and `alwaysInclude` names. |
| `ArgiNeutralAgents` | `handler(agent)` / `builder(agent)` serve an ARGI `Agent` (for example `ReActAgent`) as an `AgentHandler`: deltas and progress become `working` statuses, and a `Suspension` becomes `input-required`. |

```java
ReActAgent agent = ReActAgent.builder(ArgiNeutralAgents.withoutAgentWeaveAttributes(chatClient))
        .tools(ArgiNeutralTools.registry(registry))                       // AgentWeave tools in
        .interceptors(List.of(CatalogToolSelectionInterceptor.of(catalog)))
        .build();
AgentHandler handler = ArgiNeutralAgents.handler(agent);                  // serve with A2aAgentServer
```

- **Caller.** The handler passes `agentweave.caller`, `agentweave.taskId` and
  `agentweave.contextId` as agent inputs. `ReActAgent` hands its inputs to every tool's
  `ToolContext`, which is where the tools read the caller.
- **`withoutAgentWeaveAttributes(chatClient)`.** `ReActAgent` also forwards its inputs as
  `ChatRequest` attributes, and some ARGI chat-client adapters (Google ADK, LangChain4j streaming)
  reject any attribute. This wrapper strips the `agentweave.*` keys before the model call; tools
  still receive them.
- **Suspensions.** A `SUSPENDED` outcome stores the run (resume token, messages, outputs) under the
  task id, in memory by default (`suspensions(map)` for shared storage), and asks the suspension's
  prompts as the `input-required` question. The reply on the same task resumes the run with the
  token; its text is passed as input `agentweave.reply` (`REPLY_KEY`) and the value of a JSON part as
  `agentweave.replyData` (`REPLY_DATA_KEY`), not as a user message.
- **Execution context.** By default tenant `agentweave/default`, principal
  `agentweave/<caller>`, run id = task id; multi-tenant deployments supply
  `executionContext(...)`.

Limitations: suspensions are lost on restart unless you supply a shared store; cancelling ARGI's
tool stage doesn't interrupt a running AgentWeave call; tool search runs once per model turn on the
last user message, and `NAMES_ONLY` fallback candidates aren't offered; usage updates and tool
results aren't reported as task status. The module's `SPEC.md` has the full design.
