---
id: protoweft-agui
type: module-design
status: draft
title: protoweft-agui — AG-UI (Agent–User Interaction) agent server and client, implemented from spec 1.0
parent: protoweft-architecture
---

# protoweft-agui

## Responsibility

Speaks AG-UI, the agent ↔ user-facing-application protocol, in both roles:

- **Server (built):** serves an agent to any AG-UI frontend (CopilotKit, chat platforms, terminal
  clients) over the HTTP + SSE binding. Agents are native `AgUiAgent`s or any core `AgentHandler`.
- **Client (next):** calls remote AG-UI agents from Java, with an opt-in bridge that offers
  registry tools to them. Not built yet; see "Deviations and gaps".

## Boundary

- Depends on `protoweft-core`, `protoweft-server` and Jackson 3 only. **No other runtime
  dependency**: there is no official Java SDK, the community one (`com.ag-ui.community` 0.1.x) is
  not used, and everything AG-UI needs that the JDK lacks is written here (SSE framing, RFC 6902
  JSON Patch, the event model). Logging is `java.lang.System.Logger`.
- Test-only: `com.networknt:json-schema-validator` (Jackson 3 based) validates produced JSON
  against the official schema, vendored as `src/test/resources/.../schema-1.0.json` and pinned by
  SHA-256 in `OfficialSchema`. Tests run on the shipped Jackson version (the build pins
  `tools.jackson` on test classpaths to `jackson3Version`).
- The public API exposes no Jackson type: JSON values are plain `Map`/`List`/`String`/`Number`/
  `Boolean`/`null`.

## Core additions (protoweft-core)

- `ProtocolId.AGUI` (`"agui"`).
- `AgentTaskContext.textDelta`, `toolCall`, `toolResult` (owned by `protoweft-architecture`): the
  neutral streaming this module maps to text and tool-call events.

## Decisions

### Model and JSON

- Events are records under the sealed `AgUiEvent` (all 31 types), each with an `Envelope`
  (`timestamp`, `rawEvent`, `metadata`, and `subagentRunId` on attributable events). Messages are
  records under `AgUiMessage`, named as in the schema (`UserMessage`, `SystemMessage`, …).
- Encoding omits optional fields with no value ("absent means absent") and still writes a
  required payload that is `null` (`CUSTOM.value`, `STATE_SNAPSHOT.snapshot`); `null` inside open
  objects (state, metadata) is data and is kept.
- Decoding follows the processing model (`Decoder`): an unknown event type is dropped and an
  unknown member stripped, each with a warning; an unknown union member in a list (content part,
  message role, patch op) drops that element; an unknown outcome is stripped (absent means
  success); a known field with a value the schema rejects is fatal (`AgUiFormatException`),
  including schema bounds (integers within ±(2^53−1), token counts ≥ 0). An unknown media-source
  type is fatal because the spec closes that union.
- **Tolerance, recorded:** an optional field sent as `null` is read as absent, with a warning. The
  spec forbids sending it; the spec allows compatibility shims for peers that do.
- Numbers: decimals are read as `BigDecimal`, large integers as `BigInteger`, so values survive a
  round trip exactly. Found by fuzzing: as `double`, `1e400` became infinity and was re-sent as the
  string `"Infinity"`. Producers may not emit non-finite numbers (`IllegalArgumentException`).
- Nesting deeper than 200 levels is rejected.

### Server

- `AgUiAgentServer.builder(AgUiAgent)` → one async servlet behind `InboundAuthFilter`, path
  `/agui` by default. A core handler is served as `builder(AgUi.agent(handler))`; there is no
  `builder(AgentHandler)` overload because implicitly typed lambdas would be ambiguous.
- `POST <path>`: `application/json` required (415), `Accept` must admit `text/event-stream`
  (406), body limit 4 MiB by default (413), malformed input or another major `protocolVersion`
  → 400 JSON error before any stream. A newer `1.x` is served with a warning; unknown members are
  stripped with a warning. Then `200 text/event-stream`, `Cache-Control: no-cache`,
  `X-Accel-Buffering: no`, one compact JSON event per `data:` line, LF framing.
- `RUN_STARTED` is sent before the agent is called and declares `protocolVersion` `1.0`.
- Every emitted event passes `StreamVerifier` (run lifecycle, open/close of messages, tool calls,
  reasoning spans and messages, per-owner steps, subagent lifecycle, attribution of
  continuations, unique interrupt ids, `pendingToolCallIds` = the calls left unanswered). A call
  that would break a rule throws `IllegalStateException` in the agent; if the agent lets it
  escape, the run ends with `RUN_ERROR`. A malformed stream is never sent.
- An agent that returns without ending the run has its open items closed (innermost first) and
  the run finished; one that throws ends with `RUN_ERROR` carrying the message. `finish()` lists
  every call the run started and did not answer as `pendingToolCallIds`.
- `callClientTool` only calls tools the run input offered (the spec says a producer SHOULD).
- Disconnect: AG-UI has no cancel request, so a client stops a run by closing the stream. A
  failed write or a container async error marks the run cancelled (`isCancelled()`), drops later
  events, and runs the disconnect hook once.
- Stateless: no task store. Everything comes from `RunAgentInput`.
- CORS: off unless `allowedOrigins` is set (`"*"` allowed). Preflights from allowed origins get
  204 with `POST, GET, OPTIONS` and `Content-Type, Accept, Authorization`; others 403. Credentials
  (cookies) are never allowed cross-origin. Actual requests from unlisted origins get no CORS
  headers rather than 403, so same-origin pages behind TLS-terminating proxies keep working.
  `InboundAuthFilter` passes CORS preflights as anonymous (owned by `protoweft-architecture`).
- **Capabilities endpoint (deviation, chosen by the user):** `GET <path>/capabilities` serves the
  spec's `AgentCapabilities` document. The spec defines the document but no way to retrieve it,
  so this path is a Protoweft convention, reported as feature `protoweft/capabilities-endpoint`.
  `transport` defaults to `{"streaming": true, "httpBinary": false}`; groups are validated against
  the spec's group names.

### Serving core agent handlers (`AgUi.agent`)

| Neutral call | AG-UI |
|---|---|
| request | message = last user message; history = user/assistant messages before it; `taskId` = `contextId` = `threadId` (stable across runs, so ARGI's per-task approvals survive) |
| resumed request | a run with `resume` entries; message parts = string payloads as text, others as JSON; entries under metadata key `agui/resume` |
| `textDelta` | text message streamed (opened on first delta, closed before any other item) |
| `toolCall` / `toolResult` | `TOOL_CALL_START/ARGS/END` / `TOOL_CALL_RESULT` |
| `working(status)` | `ACTIVITY_SNAPSHOT` type `status`, id `<runId>/status`, content `{"text": …}` |
| `reply`, `complete(message)` | text message, then `RUN_FINISHED` |
| `artifact` | text parts → text message; non-text parts → `ACTIVITY_SNAPSHOT` type `artifact` |
| `requireInput` / `requireAuth` | `RUN_FINISHED` interrupt, reasons `input_required` / `auth_required` |
| `fail` / `reject` | `RUN_ERROR` / `RUN_ERROR` code `rejected` |
| disconnect | `isCancelled()` true, `onCancel(threadId)` |

`AgUi.run(task)` returns the `AgUiRun` behind a task served this way, for features the neutral
contract has no words for (state, activity, reasoning, subagents, the application's tools).

## Spec rules implemented

Event model and envelope; run input (all fields); metadata kept open; streaming triads with
reopening; snapshot–delta (JSON Patch with atomic application); interrupt–resume (producer side);
the HTTP + SSE binding including the error path and truncation (a disconnect is never reported as
success); versioning (`MAJOR.MINOR` declared both ways, newer minor served, other major rejected);
processing model on input; subagent attribution and lifecycle.

## Deviations and gaps

- `GET <path>/capabilities` (above).
- Not built: the client role, chunk expansion (`*_CHUNK` is decoded but the server never emits it),
  the HTTP + Protobuf binding, SSE keep-alive comments.

## Coverage

`AgUiFeatures` lists the feature ids; the server reports every event family, interrupts, frontend
tools, version negotiation, CORS and the capabilities endpoint as supported, and
`binding/http-protobuf` as unsupported. Tests assert it.

## Verification

- Every event type, the run input and the capabilities document validate against the vendored
  official schema; every event round-trips through encode and decode.
- End-to-end over embedded Jetty: a run using every family, early return, failures and misuse,
  cancel, input rejection (400/406/413/415), capabilities, CORS with an authenticator, disconnect.
- The `AgentHandler` adapter: streaming, history, interrupt then resume, failures, artifacts,
  `AgUi.run`, `onCancel`.
- RFC 6902 Appendix A examples and atomicity; `StreamVerifier` rule by rule.
- Jazzer fuzz targets for the run-input and event parsers and for JSON Patch, with regression
  seeds (including the fuzzer's finds: huge numbers, token counts beyond 2^53−1), in the CI fuzz
  job.

## Sources

- [AG-UI specification 1.0](https://docs.ag-ui.com/spec/1.0) and its
  [schema](https://docs.ag-ui.com/spec/1.0/schema.json) (SHA-256
  `4b5c93226838a0e72d88e6c5df20633c686c49fcb75d9be2815c6cbf9e48e71a`), read 2026-11-11.
- RFC 6902 (JSON Patch), RFC 6901 (JSON Pointer), the WHATWG Server-Sent Events grammar, the Fetch
  standard's CORS protocol.
