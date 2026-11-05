---
id: agentweave-argi-neutral
type: module-design
status: draft
title: agentweave-argi-neutral — adapter for ARGI's Spring-free API (unpublished snapshot)
parent: agentweave-architecture
---

# agentweave-argi-neutral

## Responsibility

Plugs AgentWeave into **ARGI's Spring-free neutral API** (`argi-api`, `argi-agent-api`), in three
directions:

1. **Tools in.** Any AgentWeave `ToolSource` (MCP, UTCP, A2A, local tools, a `ToolRegistry`) becomes
   an ARGI `ToolRegistry` of `Tool`s. The reverse direction turns ARGI `Tool`s into
   `LocalTools.LocalTool`s, so AgentWeave's MCP and UTCP servers can serve them.
2. **Catalog tool selection.** A `ModelInterceptor` searches a `CapabilityCatalog` for the last user
   message and shows the model only the hits. It needs no extra model call, unlike ARGI's LLM-based
   selection.
3. **Agents out.** An ARGI `Agent` (for example `ReActAgent`) becomes a core `AgentHandler`, so an
   AgentWeave agent server (for example A2A) serves it. Deltas and progress are streamed, and a
   `Suspension` becomes `input-required` and is resumed on the same task.

The Spring-based ARGI 2.1.0-RC1 has its own module (`agentweave-argi`).

## Pinned ARGI and build

- **Pinned commit.** ARGI `main` at **`a869538fcee17230bc8f20f6ed76c0d15926220e`**
  (2026-11-05, "Merge pull request #355"), version `2.1.0-RC2-SNAPSHOT`, group
  `io.github.agentic-ai-java`. The SHA lives in `scripts/install-argi-snapshot.sh`. Bump the script
  and this section together.
- **Why it isn't in the default build.**
  - The neutral API exists only on ARGI `main`, and Maven Central has only the Spring-based RC1.
  - A default build would need network access to GitHub and a Maven build of ARGI.
  - `settings.gradle.kts` therefore includes the module only with `-PargiSnapshot`.
- **Why it isn't published.**
  - A published artifact can't depend on an unpublished SNAPSHOT.
  - The root build lists it in `unpublished`, and the BOM excludes it.
  - It's published once ARGI publishes 2.1.0-RC2: then the version moves to RC2, `mavenLocal` is
    dropped and the module joins the default build.
- **Installing ARGI.** `scripts/install-argi-snapshot.sh`:
  - clones ARGI into `~/.cache/agentweave/argi-src` (`ARGI_SRC_DIR`) and checks out the pinned SHA;
  - runs `./mvnw -B -ntp -DskipTests -Dmaven.javadoc.skip=true -pl
    argi-api/argi-api-base,argi-runtime/argi-runtime-api,argi-runtime/argi-runtime-core,argi-agent/argi-agent-api,argi-agent/argi-agent-core
    -am install`. `-am` adds the parent POM `argi`;
  - writes the SHA to `~/.m2/repository/io/github/agentic-ai-java/.agentweave-argi-sha`;
  - is idempotent. It skips the build when the marker matches and every jar is present, and
    `ARGI_FORCE=1` rebuilds;
  - honours `MAVEN_REPO_LOCAL`.
- **No Spring.** None of these modules depends on Spring. The verified artifact IDs are
  `argi-api`, `argi-runtime-api`, `argi-runtime-core`, `argi-agent-api` and `argi-agent-core`.
  - Their only third-party dependencies are Jackson 2.22.4, Jackson 3.2.4 and networknt
    `json-schema-validator` 3.0.1, all in `argi-runtime-core` and `argi-agent-core`.
  - `argi-agent-core` (`ReActAgent`) is therefore usable, but only as a test dependency.
- **Build.**
  `scripts/install-argi-snapshot.sh && ./gradlew -PargiSnapshot :agentweave-argi-neutral:build`.
- **Repository.** The module's build file declares `mavenLocal()` as an `exclusiveContent`
  repository for the group `io.github.agentic-ai-java`. ARGI comes only from there, and nothing else
  does.

## Boundary

- **Dependencies.**
  - `api`: `agentweave-core`, `argi-api` and `argi-agent-api` (which brings `argi-runtime-api`).
  - `implementation`: Jackson 3 (`jackson3Version`; ARGI's 3.2.4 wins on a shared classpath).
  - `compileOnly`: `agentweave-discovery`, used only by `CatalogToolSelectionInterceptor`.
  - Test only: `argi-agent-core`.
  - No Spring and no protocol modules.
- **Public API** (package `io.agentweave.argi.neutral`):
  - `ArgiNeutralTools`:
    - `registry(ToolSource)` and `builder(ToolSource)`, with `filter`, `toolNames`,
      `callerIdentity`, `callerIdentityFrom(Function<ToolContext, CallerIdentity>)` and
      `executor`. The builder ends in `build()` (a `ToolRegistry`), `tools()` (`List<Tool>`) or
      `dynamic()` (a `Function<Map, ToolRegistry>` for `ReActAgent.Builder.dynamicTools`);
    - `toLocalTools(Collection<? extends Tool>)` and `toLocalTools(ToolRegistry)`;
    - `CALLER_KEY` (`agentweave.caller`), `PROGRESS_KEY` (`agentweave.progress`) and
      `META_RETURN_DIRECT` (`argi.returnDirect`).
  - `CatalogToolSelectionInterceptor extends ModelInterceptor`: `of(catalog)` and
    `builder(catalog)`, with `callerIdentityFrom(Function<ModelRequest, CallerIdentity>)`, `limit`,
    `keepOtherTools`, `alwaysInclude`, `queryCustomizer`, `onResult` and `executor`. Also `NAME`.
  - `ArgiNeutralAgents`:
    - `handler(Agent)` and `builder(Agent)`, with `executionContext`, `requestCustomizer`,
      `suspensions(Map<String, SuspendedRun>)` and `replayHistory`;
    - `withoutAgentWeaveAttributes(ChatClient)`;
    - `descriptor` and `skill`;
    - the record `SuspendedRun`;
    - the constants `ARTIFACT_NAME` (`response`), `REPLY_KEY`, `REPLY_DATA_KEY`, `TASK_ID_KEY`,
      `CONTEXT_ID_KEY`, `STRUCTURED_OUTPUT_KEY`, `ATTRIBUTE_PREFIX` and `AUTHORITY`.
  - `ToolNames.sanitize` and `ToolNames.PROVIDER_SAFE`.

```java
Agent agent = ReActAgent.builder(ArgiNeutralAgents.withoutAgentWeaveAttributes(chatClient))
        .tools(ArgiNeutralTools.registry(agentweaveRegistry))
        .interceptors(List.of(CatalogToolSelectionInterceptor.of(catalog)))
        .humanInTheLoop(approvals).authorizationPolicy(policy)
        .build();
AgentHandler handler = ArgiNeutralAgents.handler(agent);   // serve with A2aAgentServer
```

## Decisions

- **Verified extension points** (pinned sources):
  - `Tool { definition(); CompletionStage<ToolResult> execute(ToolCall, ToolContext); returnDirect() }`;
    `ToolDefinition(name, description, String inputSchema)`; `ToolCall(id, name, String arguments)`
    (`null` arguments become `"{}"`); `ToolResult(List<ContentPart>, boolean error, Map metadata)`;
    `ToolContext(Map attributes)` (values must be non-null, because it uses `Map.copyOf`);
    `ToolRegistry(Collection)` rejects duplicate names.
  - `Agent.stream(AgentRequest, AgentExecutionContext)` returns a `Flow.Publisher<AgentEvent>`.
  - `AgentRequest(messages, inputs, resumeToken)`.
  - `AgentEvent` is sealed: `MessageDelta`, `Message`, `Progress`, `UsageUpdate` and `Finished`.
  - `AgentOutcome` is `COMPLETED` or `SUSPENDED`, with `Suspension(resumeToken, Map prompts)`.
  - `AgentExecutionContext(TrustedExecutionContext, Clock)` is `AutoCloseable`, and
    `control().cancel()` cancels it.
  - `ReActAgent`:
    - It runs on its own thread.
    - **State.** It passes `request.inputs()` (merged with tool patches) as every tool's
      `ToolContext` and as the `ModelRequest` context and the `ChatRequest` attributes.
    - **Dynamic tools.** `dynamicTools(Function<Map, ToolRegistry>)` is resolved before each turn,
      and static tools shadow dynamic ones.
    - **Interceptor chain.** `ModelInterceptor.interceptModel(ModelRequest, ModelCallHandler)`
      wraps each model call. `ModelRequest.Builder.tools(names)` is an explicit selection and
      `dynamicToolRegistry(..)` adds tools. The set executed in a turn is exactly the effective set
      shown to the model in that turn.
    - **Authorization.** A tool denied by `ToolAuthorizationPolicy` calls
      `HumanInTheLoop.suspend(..)` when one is configured. The result is
      `Finished(SUSPENDED)`.
    - **Resume.** On resume it calls `HumanInTheLoop.validateResume` and then continues with
      `request.messages()` and `request.inputs()`. It doesn't re-execute the pending call.
    - **Tool failures.** A failed tool stage becomes `ToolResult.error("Tool execution failed")`.
- **Tools in.**
  - **Names.** Names go through `ToolNames.sanitize`, the same algorithm as
    `discovery.ProviderToolNames` (copied, because discovery is optional), so a catalog capability
    has the same name everywhere.
  - **Binding and clashes.** Each `Tool` is bound to its original name. A clash fails with
    `IllegalStateException`.
  - **Schemas.** Schemas are serialized as JSON. An empty schema, or one without `type`, becomes an
    object schema.
  - **Execution.** `execute` parses the arguments synchronously; anything that isn't a JSON object
    is an error result with category `INVALID_ARGUMENTS`. It then runs
    `ToolSource.invoke(inv, caller, progress)` through `CompletableFuture.supplyAsync` on the
    executor. The default executor is a shared cached pool of daemon threads, because AgentWeave
    calls block on I/O; the common pool would starve.
  - **Caller and progress.** The caller is `ToolContext["agentweave.caller"]`, a configured
    function, or anonymous. A `ToolProgress` under `agentweave.progress` is forwarded.
  - **Errors.** An error outcome becomes `ToolResult(parts, error = true)`. An
    `AgentWeaveException` becomes an error result carrying its message and
    `metadata["agentweave.errorCategory"]`, so the model sees the reason instead of ARGI's generic
    "Tool execution failed". Other runtime exceptions fail the stage, and ARGI's policy applies.
  - **Content.** Text stays text and JSON becomes a JSON part. `Data` becomes an IMAGE, AUDIO or
    OTHER binary part (by MIME type), a `Link` becomes its URI, and `Unmapped` becomes a visible
    marker. A structured payload with no parts becomes one JSON part.
  - **Listing.** `build()` is a snapshot. `dynamic()` lists the source again on every resolution.
- **Tools out.**
  - **Names.** They must match `[A-Za-z0-9_-]{1,64}` and be unique; invalid ones are rejected
    instead of being renamed.
  - **Metadata.** `returnDirect` is kept as metadata `argi.returnDirect`.
  - **Calls.** A call has a fresh `ToolCall` id and the arguments as JSON. The caller and progress
    go in the same two context keys.
  - **Results.** JSON parts are parsed. A result whose only part is JSON is also the structured
    payload. Binary parts become base64 `Data`. `error` is kept.
  - **Failures.** A failed stage's cause propagates. `LocalTools` reports it as a failure outcome,
    but an `AgentWeaveException` is rethrown.
- **Catalog selection** (`CatalogToolSelectionInterceptor`), per model call:
  - **Search.** The query is the last USER message's text. The caller is
    `ModelRequest.getContext()["agentweave.caller"]` (ReAct's state) or a function. The default
    limit is 5, and a `queryCustomizer` can tighten the query.
  - **Policies.** The catalog's permission, availability, ranking and `FallbackPolicy` apply.
  - **Hits.** Each hit becomes a `Tool` in the request's dynamic registry, through
    `CapabilityCatalog.invoke`, which re-checks permission and availability. It's bound to the
    caller the search ran for, so the search and the call agree.
  - **Selection.** The selection is set to the hits plus every requested tool that isn't a catalog
    capability (`keepOtherTools`, default true) plus `alwaysInclude` names. ReAct then executes
    exactly that set.
  - **Fallback and observation.** `NAMES_ONLY` candidates carry no schema and aren't added. With no
    hits, the model sees no catalog tools, and the catalog's `FallbackPolicy` (for example
    `ALL_SCHEMAS`) is the explicit fallback. `onResult` observes confidence, stage and hidden
    counts.
  - **Alternatives.** `dynamicTools(..)` alone can't search, because it sees only state, not
    messages. The interceptor is therefore the extension point. `ArgiNeutralTools.builder(..).dynamic()`
    covers re-listing.
- **Agents out** (`ArgiNeutralAgents` → `NeutralAgentHandler`):
  - **Fresh run.**
    - **Messages.** The task's history is replayed (unless `replayHistory(false)`), then the
      current message. USER messages map to USER and AGENT messages to ASSISTANT.
    - **Inputs.** `agentweave.caller`, `agentweave.taskId` and `agentweave.contextId`.
    - **Execution context.** It comes from `executionContext(..)`. The default is tenant
      `agentweave/default`, principal `agentweave/<caller>`, runId = taskId, correlationId =
      contextId, the system clock, and no deadline. The handler closes it after the run.
  - **Events.** The handler requests `Long.MAX_VALUE`, and events are queued and handled on the
    handler's thread.
    - **Statuses.** `MessageDelta` becomes `working(delta)`, and `Progress` becomes
      `working(step {details})`. An assistant `Message` with tool calls becomes
      `working("calling a, b")`. `UsageUpdate` is ignored.
  - **Completed.** The artifact `response` is the last ASSISTANT message of the outcome (else of the
    events, else the concatenated deltas), plus `outputs["structuredOutput"]` as a JSON part. The
    task then completes.
  - **Suspended.**
    - **Stored run.** `SuspendedRun(resumeToken, messages, outputs, prompts)` is stored under the
      taskId, in a configurable map (in-memory by default).
    - **Prompt.** `requireInput` gets the prompt. A single prompt's value is used as-is, several
      become sorted `key: value` lines, and none becomes "Input required".
  - **Resume.**
    - **Request.** When `request.resumed()` and a run is stored, the handler removes it (tokens are
      single-use) and sends `AgentRequest(stored messages, stored outputs + base inputs +
      agentweave.reply = text [+ agentweave.replyData = first JSON part], resumeToken)`.
    - **Reply.** The reply is an input and not a message, because a ReAct suspension sits after an
      assistant tool-call message, where a user message would be invalid for providers. A
      `requestCustomizer` can change this.
    - **Nothing stored.** A resumed task with no stored run is treated as a fresh run.
  - **Errors.** `onError`, a throwing `stream`/customizer, completion without `Finished`, or a
    blank message fail the task.
  - **Cancellation.** A cancelled task (`isCancelled`, or `onCancel(taskId)` from the server)
    cancels the `Flow.Subscription` and the execution control. `onCancel` also forgets a stored
    suspension.
- **Attributes.** ReAct forwards inputs as `ChatRequest.attributes`. ARGI's Google ADK adapter, and
  its LangChain4j streaming adapter, reject any attribute (verified in the pinned sources).
  `withoutAgentWeaveAttributes(chatClient)` strips the `agentweave.*` keys before the model call;
  tools still get them through `ToolContext`.

## Coverage and limitations

- **Unpublished and opt-in.** The module builds only with `-PargiSnapshot` after the install
  script, and isn't on Maven Central.
- **Suspensions.** They are in memory by default, so a restart or another node loses them. Supply
  `suspensions(map)` for shared storage. The token is passed through opaque: validating it is
  ARGI's `HumanInTheLoop.validateResume`.
- **Caller and tenancy.** The caller reaches tools only through ReAct state (inputs). Agents that
  don't forward inputs to `ToolContext` need `callerIdentity(..)`. The default trusted context
  uses a fixed tenant; multi-tenant deployments supply `executionContext(..)`.
- **Cancellation.** Cancelling ARGI's tool stage doesn't interrupt a running AgentWeave call (a
  `supplyAsync` limitation).
- **Tool search.** It runs once per model turn on the last user message, not per tool-result
  turn. `NAMES_ONLY` fallback candidates aren't offered.
- **Status events.** `UsageUpdate` events and tool-result messages aren't reported as task status.

## Verification

`scripts/install-argi-snapshot.sh && ./gradlew -PargiSnapshot :agentweave-argi-neutral:build`
runs 21 JUnit tests with no network after installation and no real LLM.

- **`ArgiNeutralToolsTest` (9).**
  - Names and schemas, including name parity with `ProviderToolNames` for long names.
  - Calls go through the configured executor, with the caller taken from `ToolContext`.
  - Error outcomes, `AgentWeaveException` and invalid JSON become error results with a category.
  - Content is mapped both ways.
  - Clashes and filters.
  - `dynamic()` lists the source again.
  - An ARGI `Tool` becomes a `LocalTool`, keeping the caller and progress context, the structured
    JSON, `returnDirect`, error results and failed stages.
  - Unsafe and duplicate names are rejected.
  - AgentWeave → ARGI → AgentWeave round trip.
- **`ArgiNeutralAgentsTest` (7), with a fake neutral `Agent` and no LLM.**
  - Deltas, progress and tool-call statuses stream on the handler thread. A suspension becomes
    `input-required`, and the resume carries the token, the stored messages and outputs, and the
    reply text and JSON.
  - A custom store, and prompt joining.
  - History is replayed, `replayHistory(false)` works, and a resumed task with no stored run starts
    fresh. Structured output becomes a JSON part.
  - The answer falls back to the concatenated deltas, and the customizer applies.
  - `onError`, completion without an outcome, a blank message and a throwing `stream` fail the task.
  - A cancelled task cancels the subscription.
  - `onCancel` stops a running task and forgets its suspension.
- **`ReActAgentIntegrationTest` (5), with a real `ReActAgent` from `argi-agent-core` and a scripted
  neutral `ChatClient`.**
  - AgentWeave tools are called as the A2A caller, and streamed model deltas become `working`.
  - The catalog interceptor shows only the hit plus a non-catalog tool, and the call goes through
    the catalog.
  - A scoped capability is hidden from bob but found and called for an admin.
  - `withoutAgentWeaveAttributes` hides the inputs from the client while tools still get the caller.
  - Human-in-the-loop: a denied tool suspends, becomes `input-required`, and the resume is
    validated and finishes with the reply.

## Sources

ARGI at `a869538fcee17230bc8f20f6ed76c0d15926220e`:

- `argi-api/argi-api-base/.../api/tool/{Tool,ToolRegistry,ToolResult,ToolContext}.java`.
- `api/chat/{ToolDefinition,ToolCall,ContentPart,ChatMessage,ChatClient,ChatRequest,ChatResponse,ChatEvent}.java`.
- `argi-agent/argi-agent-api/.../agent/{Agent,AgentEvent,AgentOutcome,AgentRequest,AgentExecutionContext,AgentInvocation}.java`.
- `interceptor/{ModelInterceptor,ModelRequest,ModelResponse}.java` and
  `interceptor/authorization/*`.
- `argi-runtime/argi-runtime-api/.../execution/{TrustedExecutionContext,ExecutionControl}.java`.
- `argi-agent/argi-agent-core/.../react/{ReActAgent,HumanInTheLoop}.java`.
- `argi-api/argi-adapter-google-adk/.../GoogleAdkChatClientAdapter.java` and
  `argi-adapter-langchain4j/.../LangChain4jChatClientAdapter.java` (attribute rejection).
- The root `pom.xml` (modules, `revision` 2.1.0-RC2-SNAPSHOT, flatten plugin) and the module
  `pom.xml` files (no Spring dependencies).
