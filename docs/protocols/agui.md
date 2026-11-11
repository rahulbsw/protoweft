# AG-UI

`protoweft-agui` speaks **AG-UI**, the [Agent–User Interaction protocol](https://docs.ag-ui.com):
the open, event-based standard that connects an agent to a user-facing application (a web page,
a chat platform, a terminal). One HTTP POST carries a `RunAgentInput`; the agent answers with one
ordered stream of typed events: text, tool calls, reasoning, shared state, progress.

Where MCP connects agents to tools and A2A connects agents to each other, AG-UI connects agents to
people. Frontends such as [CopilotKit](https://github.com/CopilotKit/CopilotKit) speak it.

- **Server:** `AgUiAgentServer` serves a Java agent to any AG-UI frontend over the HTTP + SSE
  binding.
- **Client:** `AgUiClient`, `AgUiThread` and `AgUiClientConnector` call remote AG-UI agents
  (LangGraph, CrewAI, Mastra, Google ADK, Pydantic AI, …) from Java, and can offer them Protoweft
  tools (MCP, UTCP, RAP, local `@AgentTool` methods) to call.

AG-UI's first-party SDKs are TypeScript, Python and .NET. Protoweft implements
[specification 1.0](https://docs.ag-ui.com/spec/1.0) directly, with **no runtime dependency
beyond the Jackson 3 the other modules already use**. Its tests check every event against the
official JSON Schema.

## Serving an agent

```java
AgUiAgentServer agui = AgUiAgentServer.builder((input, run) -> {
            AgUiEmitter.TextMessage reply = run.startMessage();
            for (String token : model.stream(input.messages())) {   // your model call
                reply.delta(token);
            }
            reply.end();
            run.finish();
        })
        .allowedOrigins("https://app.example.com")   // only if browsers call it directly
        .build();

ProtoweftServer.builder().port(8080).endpoints(agui).start();   // POST http://localhost:8080/agui
```

The agent is called once per run on a worker thread, after `RUN_STARTED` has been sent. The
server keeps nothing between runs: the conversation, the shared state and the application's tools
all arrive in `RunAgentInput` each time.

### What an agent can send

`AgUiRun` (and each subagent) offers one method per protocol feature:

| Method | Events |
|---|---|
| `startMessage()` → `delta`, `end`; `say(text)` | `TEXT_MESSAGE_START/CONTENT/END` |
| `startToolCall(name)` → `args`, `end`; `toolCall(name, args)`; `toolResult(id, content)` | `TOOL_CALL_*`, `TOOL_CALL_RESULT` (tools the agent runs itself) |
| `callClientTool(name, args)` | a call to one of the application's tools, left for the application to run |
| `startReasoning()` → `startMessage()`; `reasoningEncryptedValue(...)` | `REASONING_*` |
| `startStep(name)`, `finishStep(name)` | `STEP_STARTED/FINISHED` |
| `stateSnapshot(value)`, `stateDelta(patch)`, `messagesSnapshot(messages)` | `STATE_SNAPSHOT`, `STATE_DELTA` (RFC 6902, see `JsonPatch.op`), `MESSAGES_SNAPSHOT` |
| `activity(id, type, content)`, `activityDelta(id, type, patch)` | `ACTIVITY_SNAPSHOT/DELTA` |
| `startSubagent(name, description)` | `SUBAGENT_STARTED`; the subagent's own events are attributed to it |
| `custom(name, value)`, `raw(event, source)` | `CUSTOM`, `RAW` |

End the run once:

| Method | Ends with |
|---|---|
| `finish()` / `finish(result)` | `RUN_FINISHED`; application tool calls still unanswered are listed in `pendingToolCallIds` |
| `interrupt(interrupts)` | `RUN_FINISHED` with the interrupt outcome; the next run answers with `resume` entries |
| `cancel()` | `RUN_FINISHED` with the cancelled outcome (open items are closed first) |
| `fail(message, code)` | `RUN_ERROR` |

The rules:

- **The stream is always valid.** Every event is checked against the specification's sequence
  rules before it is sent. For example, content for a message that is not open, a step closed
  twice, or finishing with a tool call still open: the call throws `IllegalStateException`, and if
  the agent lets it escape, the run ends with `RUN_ERROR`.
- An agent that returns without ending the run has its open items closed and the run finished.
  One that throws ends the run with `RUN_ERROR` carrying the exception message.
- `callClientTool` accepts only tools the application offered in this run's input.
- **Cancellation.** AG-UI has no cancel request: a client stops a run by closing the stream.
  `run.isCancelled()` then turns true and later events are dropped. Long-running agents should
  check it.

### Serving an existing agent handler

Any core `AgentHandler`, including one already served over A2A and the Spring AI, LangChain4j and
ARGI handlers, can be served over AG-UI as well:

```java
AgUiAgentServer agui = AgUiAgentServer.builder(AgUi.agent(handler)).build();
```

| Handler call | AG-UI |
|---|---|
| (request) | the last user message, with the earlier user and assistant messages as history; `taskId` and `contextId` are the `threadId` |
| `textDelta(text)` | a streamed text message |
| `toolCall`, `toolResult` | tool call events |
| `working(status)` | an activity message of type `status` |
| `reply`, `complete(message)`, text artifacts | text messages |
| non-text artifacts | an activity message of type `artifact` |
| `requireInput`, `requireAuth` | an interrupt (`input_required`, `auth_required`); the next run's `resume` answers arrive as a resumed request |
| `fail`, `reject` | `RUN_ERROR` (`reject` with code `rejected`) |

A handler reaches AG-UI-only features (shared state, activity, reasoning, subagents, the
application's tools) through `AgUi.run(task)`, which is empty when the task is served over
another protocol.

### Spring AI, LangChain4j and ARGI agents

Their handlers know when they are served over AG-UI:

| | Conversation | Frontend tools | Tool calls shown | Approvals |
|---|---|---|---|---|
| [Spring AI](../integrations/spring-ai.md#agents-out-over-ag-ui) `ChatClient` | from each run | yes | Protoweft tools | — |
| [LangChain4j](../integrations/langchain4j.md#over-ag-ui) AI Service | from each run, with `LangChain4jAgents.chatMemory(...)` given to the AI Service and the handler | not yet | yes (streaming) | — |
| [ARGI](../integrations/argi.md#agents-over-ag-ui-with-approvals-as-interrupts) agent | ARGI's checkpoint | not yet | — | as interrupts |

### Spring Boot

```properties
protoweft.server.agui.enabled=true
protoweft.server.agui.allowed-origins=https://app.example.com
protoweft.server.agui.from-chat-client=true          # serve the ChatClient, if there is no AgentHandler
protoweft.agui.agents.planner.url=https://agents.example.com/agent   # a remote AG-UI agent, as planner.ask
```

See the [property reference](../integrations/spring-boot.md#property-reference).

### Builder options

| Option | Default | |
|---|---|---|
| `path(String)` | `/agui` | the run endpoint; capabilities are at `<path>/capabilities` |
| `allowedOrigins(String...)` | none | browser origins allowed cross-origin; `"*"` for any |
| `capabilities(Map)` | `transport` only | the agent's capabilities document (see below) |
| `maxRequestBytes(int)` | 4 MiB | larger inputs get 413 |
| `executor(ExecutorService)` | a cached daemon pool owned by the server | runs agents |
| `observations(ObservationHandler)` | none | one observation per run (`agent/run`) |
| `endpointName(String)` | `agui` | unique per host |

### HTTP behaviour

- `POST <path>` with `Content-Type: application/json` and an `Accept` that admits
  `text/event-stream` answers `200 text/event-stream`, one JSON event per `data:` line.
- Input rejected before the run starts gets a JSON error and no stream: 400 for malformed input or
  a protocol major version other than 1, 406, 413, 415.
- A client that declares a newer `1.x` is served (the stream declares `1.0`). Fields the
  specification does not describe are ignored, with a warning in the log.
- Authentication is the host's `InboundAuthenticator`, as for every Protoweft endpoint.

### Browsers and CORS

Cross-origin access is off until you list origins with `allowedOrigins`. Then preflights from
those origins get `204` with `POST, GET, OPTIONS` and the headers `Content-Type, Accept,
Authorization`, and responses carry `Access-Control-Allow-Origin`. Cookies are never allowed
cross-origin: browser clients send an `Authorization` header. Preflights pass the authenticator
(browsers cannot attach credentials to them); the run itself is authenticated as usual.

### Capabilities

`GET <path>/capabilities` returns the agent's capabilities document, the specification's
`AgentCapabilities`:

```java
.capabilities(Map.of(
        "identity", Map.of("name", "Weather", "description", "Forecasts"),
        "humanInTheLoop", Map.of("supported", true, "approvals", true)))
```

!!! note "A Protoweft convention"
    The specification defines the capabilities document but deliberately no way to fetch it, so
    other AG-UI implementations will not look for this endpoint. Declare only what the agent does;
    an omitted field means "not declared", not "unsupported".

## Calling an agent

### One run: `AgUiClient`

```java
AgUiClient agent = AgUiClient.builder("https://agents.example.com/agent")
        .credentials(credentials)            // or .authorizer(oauthClient)
        .build();

RunResult result = agent.run(RunAgentInput.of(threadId, runId, messages), event -> {
    if (event instanceof AgUiEvent.TextMessageContent text) {
        System.out.print(text.delta());
    }
});
```

Every stream goes through the specification's consumer pipeline before your listener sees an
event:

- Event types and fields the client does not recognise are **dropped and stripped**, each reported
  as a warning (`RunResult.warnings()`, and the builder's `warnings` callback).
- **Chunk events** (`TEXT_MESSAGE_CHUNK`, …) arrive expanded into start, content and end events.
- The sequence is **verified**. A malformed value or a sequence violation fails the call with a
  `ProtoweftException` (`PROTOCOL`).

`RunResult` says how the run ended: `succeeded()`, `interrupted()` with `interrupts()`,
`cancelled()`, `error()` (the agent's `RUN_ERROR`), or `truncated()`. Truncated means the stream
ended without an outcome, which is never reported as success. `pendingToolCalls()` lists the calls
the application must answer. `call(input)` returns a `Call` you can `cancel()` from another thread,
and `publisher(input, executor)` offers the run as a `Flow.Publisher` with backpressure. The
stream is read on its own thread, and the executor only delivers events, so any executor works.
Cancelling the subscription closes the stream at once. Outgoing
input never carries activity messages and always declares a protocol version.

### A conversation: `AgUiThread`

AG-UI servers keep nothing between runs, so the client holds the conversation. `AgUiThread`
assembles messages from the events (tool calls attached to their message, encrypted reasoning
stored where it belongs, `MESSAGES_SNAPSHOT` reconciled by id), keeps the shared state (snapshots
and JSON Patch deltas, applied atomically), and sends both back on every run:

```java
AgUiThread thread = AgUiThread.builder(agent)
        .tools(registry, spec -> spec.name().startsWith("weather."))   // offer Protoweft tools
        .approval(call -> ui.confirm(call.tool().name(), call.arguments()))
        .build();

AgUiThread.Turn turn = thread.send("Will it rain in Oslo tomorrow?");
switch (turn.status()) {
    case COMPLETED -> show(turn.text());
    case INTERRUPTED -> thread.resume(Map.of(turn.interrupts().get(0).id(), ui.ask(turn.interrupts().get(0))));
    case AWAITING_TOOL_RESULTS -> { /* answer(...) the application's calls, then continueRun() */ }
    default -> showProblem(turn);
}
```

A turn can span several runs: when the agent leaves calls to offered tools pending, the thread runs
them and continues by itself (at most `maxAutomaticRuns`, default 10).

### Offering Protoweft tools to the agent

`tools(source, filter)` offers tools from any `ToolSource` (a `ToolRegistry` of MCP servers, UTCP
manuals, RAP tool servers, A2A agents, local `@AgentTool` methods) as the run's application tools.
A Python LangGraph agent can then call tools only the Java side can reach.

- **Names** are adapted for model providers: `weather.forecast` is offered as
  `weather__forecast`.
- **Consent.** A tool marked `readOnly` runs automatically. Any other tool runs only if the
  `approval` callback approves the call. Without an approval step, the agent is told the call
  needs approval and nothing runs.
- **Untrusted arguments.** Arguments are model output. They must be a JSON object and pass a
  structural check against the tool's schema (required members, types, enums) before approval is
  asked or the tool runs. A failed check answers the call with an error.
- Results go back as tool messages (a failure carries `error`). Tool subscriptions are dropped:
  AG-UI has no channel for later events.
- Calls to tools you declared yourself with `clientTools(tools, handler)` (for example UI actions)
  go to `handler`. If it has no answer, the turn stops with `AWAITING_TOOL_RESULTS` until you
  `answer(...)` and `continueRun()`.

### Interrupts

An interrupted run returns `INTERRUPTED`. Continue with `resume(answers)`, or abandon an
interrupt with a `ResumeEntry` of status `cancelled`. The thread checks before sending that every
open interrupt is covered exactly once, and refuses to resolve one whose `expiresAt` has passed (it
can still be abandoned). Sending a new message while interrupts are open is refused.

### As a Protoweft agent: `AgUiClientConnector`

```java
AgUiClientConnector remote = AgUiClientConnector.builder("https://agents.example.com/agent")
        .conversations(b -> b.tools(registry, spec -> true).approval(approval))
        .build();
remote.start();
AgentReply reply = remote.send(AgentMessage.user("Plan my trip"));
registry.register("planner", remote);   // offers one tool, "ask"
```

| AG-UI | `AgentReply` |
|---|---|
| finished | `COMPLETED`, the assistant's text as the message |
| interrupted | `INPUT_REQUIRED`, the interrupts' messages. Reply on the same task: the text answers a single interrupt; a JSON part `{interruptId: answer}` answers several. |
| tool calls the client does not run | `INPUT_REQUIRED`; the next message's text answers them |
| `RUN_ERROR` | `FAILED` |
| cancelled run / stream ended early | `CANCELED` / `UNKNOWN` |
| the automatic-run limit hit with work left | `WORKING`; the next message continues the turn instead of being added |

The conversation id is both the task id and the context id. `getTask` is unsupported (AG-UI has no
tasks); `cancel` closes the stream of the run in flight; `thread(taskId)` gives the `AgUiThread`.
While streaming, the assistant's text arrives as chunks of a `response` artifact.

## Checking an AG-UI agent

AG-UI has no official conformance suite, so `protoweft-agui` ships a checker for the producer
rules of specification 1.0. It works against any AG-UI agent, whichever language it is written in:

```bash
jbang agui-conformance@rahulbsw/protoweft https://agents.example.com/agent --bearer "$TOKEN"
```

or from Java (for example in your own tests):

```java
AgUiConformance.Report report = AgUiConformance.builder(URI.create("http://localhost:8080/agui")).build().run();
assertThat(report.passed()).as(report.text()).isTrue();
```

It sends a few runs and checks:

- **The binding:** 200 with `text/event-stream`, LF framing, one event per `data:` frame.
- **Event shapes:** only shapes the specification describes, with absent fields omitted rather
  than `null`. A producer must not rely on consumers stripping extras, so extras fail here.
- **The run lifecycle:** ordering, a terminal event, and the input's thread and run ids.
- **The version:** declared on `RUN_STARTED`, and a newer minor still served.
- **Input:** unknown input members served; malformed or incomplete input rejected before the
  stream starts.

`--json` prints a machine-readable report; the exit code is 0 only when every check passes.
Protoweft's own server is checked on every pull request, and so is an agent built on the official
Python SDK; see [Conformance testing](../operations/conformance.md).

## Example: a browser page and CopilotKit

`examples/agui-web` serves a Spring AI agent over AG-UI. Its page uses the official
`@ag-ui/client` from the browser and shows:

- streamed text;
- the agent's tool calls, as cards;
- a frontend tool the page runs itself (`set_theme`);
- the shared state.

To use the same agent from [CopilotKit](https://docs.copilotkit.ai), register its URL as an AG-UI
`HttpAgent` in CopilotKit's runtime:

```ts
import { HttpAgent } from "@ag-ui/client";
const runtime = new CopilotRuntime({
  agents: { assistant: new HttpAgent({ url: "http://localhost:8080/agui" }) },
});
```

## Coverage

| | Server | Client |
|---|---|---|
| HTTP + SSE binding | yes | yes |
| HTTP + Protobuf binding | no | no |
| All 31 event types, all eight families | yes | yes |
| Chunk events | never sent | expanded |
| Interrupt and resume | yes | yes, with coverage and expiry checks |
| Frontend (application) tool calls | yes | yes, with the tool bridge |
| Version negotiation (`protocolVersion`) | yes | yes |
| Capabilities document | `GET <path>/capabilities` | read when the server has that endpoint |

`compliance()` on `AgUiAgentServer`, `AgUiClient` and `AgUiClientConnector` reports this with the
feature ids in `AgUiFeatures`.
