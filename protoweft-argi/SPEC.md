---
id: protoweft-argi
type: module-design
status: draft
title: protoweft-argi — ARGI 2.1.0-RC1 adapter (tools in, catalog tool selection, agents over A2A)
parent: protoweft-architecture
---

# protoweft-argi

## Responsibility

Plugs Protoweft into **ARGI 2.1.0-RC1** (`io.github.agentic-ai-java`, packages
`io.github.agentic.ai.graph.*`) in three directions:

1. **Tools in.** Any Protoweft `ToolSource` becomes tools of an ARGI `ReactAgent`.
2. **Catalog tool selection.** A `ModelInterceptor` narrows the tools offered on each model call to
   what the `CapabilityCatalog` finds. It replaces ARGI's `ToolSelectionInterceptor`, which makes an
   extra LLM call.
3. **Agents out.** An ARGI `Agent` becomes a core `AgentHandler`, so an Protoweft agent server (for
   example A2A) serves it. Human-in-the-loop interrupts become `input-required`.

## Boundary

- **Dependencies.**
  - `api`: `protoweft-spring-ai`, which is reused for every callback, and `argi-agent-framework`
    2.1.0-RC1. `ReactAgent`, `Agent`, `ModelInterceptor` and `RunnableConfig` appear in the API.
  - `implementation`: `spring-ai-client-chat` and `spring-ai-tool-search-tool` 2.0.2, pinned so
    ARGI's 2.0.1 Spring AI lines up with the `spring-ai-model` 2.0.2 that `protoweft-spring-ai`
    exports; `tool-search-tool` is needed only because `CatalogToolIndex` implements `ToolIndex`.
    Also Jackson 3 (unversioned) and `slf4j-api`.
  - `compileOnly`: `protoweft-discovery`, needed only by `CatalogToolSelectionInterceptor`.
  - No Spring Boot and no protocol module. ARGI itself brings Jackson 2 and its own old A2A
    client (`io.github.a2asdk` 0.3.3). Those packages differ from `org.a2aproject.sdk` 1.5.0, so
    they coexist with `protoweft-a2a`.
- **Public API** (package `io.github.rahulbsw.protoweft.argi`):
  - `ArgiTools`: `callbackProvider(ToolSource)` (an `ProtoweftToolCallbacks`),
    `withCaller(RunnableConfig.Builder, CallerIdentity)`, `callerFrom(Map)`, `CALLER_KEY`
    (`protoweft.caller`).
  - `CatalogToolSelectionInterceptor extends ModelInterceptor`: `from(catalog)`, `builder(catalog)`
    with `limit`, `contextMessages`, `alwaysVisible`, `keepUncataloguedTools`,
    `whenNothingFound(NoMatch.KEEP_ALL|KEEP_NONE)`, `registerCatalogTools`, `toolNames`,
    `callerIdentity`, `callerIdentityFrom(Function<ModelRequest, CallerIdentity>)`,
    `queryCustomizer` and `onResult`. Also `getTools()`, `toolCallbacks()` and `NAME`.
  - `ArgiAgents`: `handler(Agent)`, `streamingHandler(Agent)`, and `builder(Agent)` with
    `streaming`, `threadId(Function<AgentRequest, String>)` and
    `config(BiConsumer<AgentRequest, RunnableConfig.Builder>)`. Also `ARTIFACT_NAME` (`response`).

```java
ReactAgent agent = ReactAgent.builder().name("bank").model(chatModel)
        .toolCallbackProviders(ArgiTools.callbackProvider(registry))        // or, from the catalog:
        .interceptors(CatalogToolSelectionInterceptor.builder(catalog).limit(5).build())
        .hooks(HumanInTheLoopHook.builder().approvalOn("bank__transfer", "Moves money").build())
        .saver(new MemorySaver())
        .build();
AgentHandler handler = ArgiAgents.handler(agent);   // serve with A2aAgentServer
```

## Decisions

- **Callbacks come from `protoweft-spring-ai`.** ARGI RC1 takes Spring AI `ToolCallback`s, so
  `ProtoweftToolCallbacks` and `CatalogToolIndex.toolCallbacks()` are reused as they are. Names,
  schemas, error handling and result rendering are therefore identical to the Spring AI adapter
  (`weather.forecast` becomes `weather__forecast`). `HumanInTheLoopHook.approvalOn` takes these
  provider names.
- **The caller travels in the run's metadata.** ARGI's tool node builds each `ToolContext` from
  the agent's static `toolContext(Map)` plus `RunnableConfig.metadata()`, and the LLM node puts
  the same metadata into `ModelRequest.getContext()`. A `CallerIdentity` under `protoweft.caller`
  is therefore seen by the callbacks (as Spring AI's adapter already reads it) and by the
  interceptor, with no ARGI-specific code in the callbacks. `ArgiAgents` sets it from
  `AgentRequest.caller()` for every run, including resumed ones. A fixed caller can go in the
  builder's `toolContext`.
- **Catalog selection.**
  - **Query.** The query is the text of the latest `UserMessage`, optionally preceded by up to
    `contextMessages` earlier user or assistant texts. The limit defaults to 5, and the caller is
    the request's.
  - **Per call.** The search runs on every model call, including the calls after tool results,
    and costs no model call.
  - **What is filtered.** Only catalog tools are filtered: a request tool counts as a catalog
    tool if its name equals a card's mapped name.
  - **Other tools.** Tools that are not in the catalog (hook or interceptor tools, plain
    callbacks) are kept by default (`keepUncataloguedTools`), because the catalog can't rank
    them. `alwaysVisible` names are always kept.
  - **Fallback stages.** Hits at every fallback stage are offered (`BROADENED`, and
    `ALL_SCHEMAS`, which is every permitted capability). `NAMES_ONLY` candidates are not, which
    matches `CatalogToolIndex`.
  - **Nothing found.** The policy also applies when the search throws or there is no user text.
    `KEEP_ALL` (the default, like ARGI's selector, which keeps every tool on failure) offers
    every catalog tool the caller may see whose source is available. `KEEP_NONE` offers no
    catalog tools.
  - **Hidden tools.** Permission-hidden tools are never offered, either from hits or from the
    fallback.
  - **Offering no tools.** ARGI reads an empty `ModelRequest.tools` as "all tools", so "no tools"
    is sent as a single name that matches no callback.
  - **Registration.** `getTools()` returns the catalog's callbacks, so ARGI registers them on the
    agent when it is built. Without this the hits couldn't be executed. `registerCatalogTools(false)`
    leaves registration to the application.
  - **Calls.** Calls go through `CapabilityCatalog.invoke`, which re-checks permission and
    availability. A hidden name the model guesses still fails.
- **Compared with ARGI's `ToolSelectionInterceptor`.**
  - **Selection.** ARGI's asks a selection `ChatModel` to pick from names and descriptions, which
    costs an extra LLM call and latency per model call. On any failure it falls back to all tools.
  - **Other filters.** It has no permission or availability filter. It only filters
    `ModelRequest.tools` by `maxTools`/`alwaysInclude`.
  - **This interceptor.** It ranks deterministically from the catalog index (keyword or hybrid),
    filters by the caller's scopes and source availability, and reports the confidence, stage and
    hidden counts through `onResult`.
- **Agents.**
  - **Thread.** The thread id is the A2A task id by default, so a paused run resumes on the same
    thread. `threadId(r -> r.contextId())` keeps one ARGI conversation per A2A context instead.
  - **Input.** The message text is the user message.
  - **Blocking and streaming.** Blocking runs use `invokeAndGetOutput`. Streaming uses `stream`,
    and `AGENT_MODEL_STREAMING` text chunks become `working` statuses. Events are queued and handled
    on the handler thread, as in `SpringAiAgents`, and cancellation disposes the subscription.
  - **Answer.** The answer is the agent's `outputKey` value, else the last `AssistantMessage`, as
    a `response` artifact. Then the task completes.
  - **Errors.** Exceptions fail the task with the root cause's message. ARGI's default
    `throwOnModelError(false)` turns a model error into an `"Exception: ..."` assistant answer, so
    set `throwOnModelError(true)` if model errors should fail the task.
- **Human in the loop.**
  - **Pausing.** An `InterruptionMetadata` with `toolFeedbacks()` pauses the task with
    `requireInput`. The question lists each pending call: name, tool-call id, arguments and the
    hook's description. Then it explains how to answer.
  - **Pending state.** The interruption is kept in memory, keyed by task id, until the task
    resumes or is cancelled.
  - **Replies.** `ToolApprovals` reads the reply:
    - text `approve`/`yes`/`ok` gives `APPROVED`, and `reject <reason>`/`no` gives `REJECTED`
      with the reason as the feedback description, which the hook tells the model;
    - a JSON data part `{"decision":"approved|rejected|edited","id":"<tool call id>",
      "arguments":{...},"reason":"..."}`, a list of them, or `{"decisions":[...]}` decides calls
      one by one. `id` may be omitted when one call is undecided, and `name` selects by tool name
      when it is unique.
    - `EDITED` needs `arguments`, and objects are serialized to JSON.
  - **Several calls.** The handler resumes only when every pending call is decided. Until then
    it keeps the partial decisions and asks again for the rest. An unparsable reply also asks
    again, stating the problem. This differs from ARGI, which re-interrupts for every call when
    feedback is partial.
  - **Resuming.** The handler follows ARGI's `HumanInTheLoopTest`: it builds
    `InterruptionMetadata.builder().nodeId(..).state(..)` with one `ToolFeedback.builder(original)
    .result(..)` per call, then passes it under `RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY` on
    the same `threadId`, with empty input.
  - **Unsupported interrupts.** Interrupts without tool feedbacks, for example `interruptBefore`,
    fail the task, since there is nothing to approve.
  - **Unknown runs.** A resumed request with no paused run (for example after a restart) fails
    with a clear message instead of running the dangling tool calls.
- **A saver is required.** ARGI resumes from the checkpoint saver. A handler for an agent
  compiled without one fails at build time with `IllegalArgumentException` naming `MemorySaver`,
  instead of failing at the first approval.

- **Dependency overrides.** ARGI 2.1.0-RC1 pulls `org.jsoup:jsoup` 1.15.4 through
  `flexmark-html2md-converter` 0.64.8; that version has two advisories (GHSA-65r4-943x-97jj, memory
  exhaustion on deeply nested namespaces; GHSA-pmhh-3w7g-xqp8, Cleaner bypass), fixed in 1.23.2.
  This module declares jsoup `jsoupVersion` (1.24.1) as a runtime dependency, so Gradle resolves
  the newer version and Maven's nearest-wins does too. `JsoupOverrideTest` checks the converter
  still works on it. Drop the override once ARGI ships a fixed jsoup.

## Coverage and limitations

- **Release candidate.** This targets the published RC1 only. ARGI main has moved to Spring-free
  `argi-api`/`argi-agent-api` (2.1.0-RC2-SNAPSHOT). Package names, `ModelRequest`,
  `InterruptionMetadata` and the HITL resume pattern may change in RC2. The neutral-API adapter is
  planned as the separate `protoweft-argi-neutral` module.
- **Tool snapshots.** ARGI calls `getToolCallbacks()` and `getTools()` once, when the agent is
  built. Tools that a source or the catalog gains later need a new agent, and dynamic listing
  doesn't apply.
- **Paused runs.** The pending calls live in the handler's memory, and the checkpoint in the
  agent's saver. After a restart a persistent saver may still hold the checkpoint, but the handler
  no longer knows the pending calls, so the resumed task fails. A task that is never answered
  keeps its entry until it is cancelled.
- **Question format.** The `requireInput` question is text only, because core's `requireInput`
  has no data parts. Pending calls are listed in prose, not as structured data.
- **Interrupts.** Only tool-approval interrupts are mapped. Streaming reports model text chunks
  only; tool execution isn't reported as status.
- **Errors.** By default model errors become answers (ARGI's `throwOnModelError(false)`).

## Verification

`./gradlew :protoweft-argi:build` runs 22 JUnit tests with no network and no LLM. A scripted
Spring AI `ChatModel` returns tool-call `AssistantMessage`s and then text, and drives real RC1
`ReactAgent`s.

- **`ArgiToolsTest` (3).**
  - Registry tools are called through `toolCallbackProviders`, with the caller from the run's
    metadata, and a fixed caller from `toolContext`.
  - A failure outcome reaches the model as text.
  - `callerFrom` is checked.
- **`CatalogToolSelectionInterceptorTest` (6).** Each test checks the `ChatRequest` options the
  model received.
  - Only the hits are offered, and the hit's call runs as the caller.
  - The scoped vault tool is never offered to an anonymous caller, even on the `KEEP_ALL`
    fallback, but it is offered and callable for a caller with the scope.
  - The `ALL_SCHEMAS` stage offers every permitted capability.
  - `KEEP_NONE` is checked with an uncatalogued tool, with `keepUncataloguedTools(false)`, with
    `alwaysVisible`, and with no tools at all.
  - A fixed caller and the query customizer apply.
  - Query text with context is checked, and `getTools()` registration can be turned on and off.
- **`ArgiAgentsTest` (9).** Each test runs a real `A2aAgentServer` in Jetty with an
  `A2aAgentConnector` client over HTTP.
  - **Approve.** `HumanInTheLoopHook` plus `MemorySaver` gives `INPUT_REQUIRED` with the pending
    tool's name, id, arguments and description. `approve` on the same task runs the tool as the
    authenticated caller and completes.
  - **Reject.** `reject <reason>` never runs the tool, and the model sees the reason.
  - **Edit.** A JSON data part with edited arguments runs the tool with the new arguments.
  - **Several calls.** Two calls in one message are decided one by one with JSON, with an
    unparsable reply in between that asks again. Only the approved call runs.
  - **Streaming.** The streaming handler resumes an approval and reports text chunks as
    `working`.
  - **Catalog over A2A.** The A2A caller's scopes reach catalog selection and the tool.
  - **Model errors.** They fail the task with `throwOnModelError(true)` and complete with the
    `Exception:` answer by default.
  - **Unknown run.** A resumed task the handler has no paused run for fails.
  - **No saver.** An agent without a saver is rejected.
- **`ToolApprovalsTest` (4).** Reply parsing is checked: text, id/name/only-open targeting, the
  `decisions` wrapper, error messages that keep the earlier decisions, and the question format.

`examples/argi-example` (3 tests) runs the Boot application, with a scripted `@Primary`
`ChatModel`, over A2A:

- the catalog offers `local__balance` and not `local__transfer` for a balance question;
- a transfer pauses as `INPUT_REQUIRED`, then `approve` runs it;
- a rejected transfer never runs.

## Sources

ARGI **v2.1.0-RC1**: the repository tag (`git archive v2.1.0-RC1`), checked against the Maven
Central POMs of `argi-agent-framework`, `argi-graph-core` and the `argi` parent (Spring AI 2.0.1,
Boot BOM import, a2a-java-sdk 0.3.3). Files read:

- `agent/Agent.java`: `invokeAndGetOutput`/`stream(String, RunnableConfig)`,
  `buildMessageInput` (an empty string adds no message), `getAndCompileGraph`.
- `agent/ReactAgent.java`: `call`, `doStream` (checkpoint rewind on cancel).
- `agent/Builder.java`: `tools`, `toolCallbackProviders`, `toolContext`, `saver`,
  `interceptors`, `hooks`, `throwOnModelError`, and `buildConfig` (saver goes in `SaverConfig`).
- `agent/DefaultBuilder.java`: `gatherLocalTools` (providers are read once; interceptor
  `getTools()` are registered).
- `agent/node/AgentLlmNode.java`: the `ModelRequest` context is state plus config metadata;
  `filterToolCallbacks` reads empty tools as all; `throwOnModelError`.
- `agent/node/AgentToolNode.java`: `ToolCallRequest.context` is the config metadata, merged into
  the `ToolContext`.
- `agent/interceptor/ModelInterceptor.java`, `ModelRequest.java`, `ModelCallHandler.java`, and
  `toolselection/ToolSelectionInterceptor.java`.
- `agent/hook/hip/HumanInTheLoopHook.java`: `approvalOn`, feedback matching by id and name,
  rejection text from the description, and re-interrupt on partial feedback.
- `graph/action/InterruptionMetadata.java`: `toolFeedbacks`, `ToolFeedback.builder(original)`,
  `FeedbackResult`.
- `graph/RunnableConfig.java` (`HUMAN_FEEDBACK_METADATA_KEY`, `threadId`, `addMetadata`),
  `GraphRunnerContext.java` (feedback metadata triggers resume from the saver), `CompileConfig.java`
  (`checkpointSaver()`), and `streaming/StreamingOutput.java`, `OutputType.java`.
- Test `agent/hooks/hip/HumanInTheLoopTest.java`: the resume pattern.
