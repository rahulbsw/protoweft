# AG-UI

`protoweft-agui` speaks **AG-UI**, the [Agent–User Interaction protocol](https://docs.ag-ui.com):
the open, event-based standard that connects an agent to a user-facing application (a web page,
a chat platform, a terminal). One HTTP POST carries a `RunAgentInput`; the agent answers with one
ordered stream of typed events: text, tool calls, reasoning, shared state, progress.

Where MCP connects agents to tools and A2A connects agents to each other, AG-UI connects agents to
people. Frontends such as [CopilotKit](https://github.com/CopilotKit/CopilotKit) speak it.

- **Server:** `AgUiAgentServer` serves a Java agent to any AG-UI frontend over the HTTP + SSE
  binding.
- **Client:** coming next (calling remote AG-UI agents from Java, with registry tools offered to
  them).

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

## Coverage

| | Server |
|---|---|
| HTTP + SSE binding | yes |
| HTTP + Protobuf binding | no |
| All 31 event types, all eight families | yes |
| Interrupt and resume, frontend tool calls | yes |
| Version negotiation (`protocolVersion`) | yes |
| Capabilities document | yes, at `GET <path>/capabilities` |
| Client role | not yet |

`AgUiAgentServer.compliance()` reports this with the feature ids in `AgUiFeatures`.
