# A2A

`agentweave-a2a` speaks [Agent2Agent](https://a2a-protocol.org) specification **1.0** over the
**JSON-RPC binding**, in both roles, on the official a2a-java SDK (`org.a2aproject.sdk`
`1.5.0.Final`):

- **Client:** `A2aAgentConnector` talks to one remote agent through the core `AgentConnector` API,
  and also exposes it as a `ToolConnector` with one `ask` tool.
- **Server:** `A2aAgentServer` publishes an AgentWeave `AgentHandler` as an A2A agent with the full
  task lifecycle.

SDK types never appear in AgentWeave's public API. The server is wired from the SDK's
framework-neutral modules; no CDI container, Quarkus or Vert.x is involved.

## Client

```java
try (A2aAgentConnector agent = A2aAgentConnector.builder("https://travel.example.com")
        .credentials((protocol, target) -> List.of(new Credential.BearerToken(token)))
        .timeout(Duration.ofSeconds(120))
        .build()) {
    agent.start();                                  // fetches the agent card
    AgentReply reply = agent.send(AgentMessage.user("Find a flight to Berlin"));
    if (reply.state().isInterrupted()) {
        reply = agent.send(AgentMessage.userReply(reply.taskId(), reply.contextId(), "Tomorrow morning"));
    }
    System.out.println(reply.text());
}
```

| Builder method | Default | Meaning |
|---|---|---|
| `builder(agentBaseUrl)` | — | The card is read from `<agentBaseUrl>/.well-known/agent-card.json`. |
| `credentials(provider)` | none | Looked up with the base URL as target, for the card fetch and for every call. `BearerToken` → `Authorization: Bearer <token>`; `NamedSecret(name, value)` → header `name: value`. |
| `timeout(duration)` | 60 s | The connect timeout, and the longest any call waits for a terminal or interrupted task (`TIMEOUT`). |
| `streaming(boolean)` | `true` | Whether `send(message, events)` may stream when the card declares `streaming`. |
| `acceptedOutputModes(modes)` | none (the agent decides) | Media types this client accepts in replies. |
| `observations(handler)` | none | Observability hook. |

`start()` fetches the card (redirects aren't followed), picks its JSON-RPC interface, and checks its
protocol version:

- No JSON-RPC interface → `UNSUPPORTED`.
- A protocol version with a major other than 1 → `VERSION_MISMATCH`.
- Otherwise the interface's `protocolVersion` becomes the negotiated revision.

After `start()`:

| Method | Behavior |
|---|---|
| `descriptor()` | The card as an `AgentDescriptor` (name, description, version, skills). |
| `send(message)` | A blocking `SendMessage`. If the server answers before the task settles, the client polls `GetTask` until it is terminal or interrupted. |
| `send(message, events)` | `SendStreamingMessage`, delivering `AgentEvent`s, when `streams()` is true; otherwise the same as `send(message)` with no events. |
| `streams()` | Whether the builder allows streaming and the card declares it. |
| `resubscribe(taskId, events)` | `SubscribeToTask`: re-attach to a running task's stream. `UNSUPPORTED` when not streaming. |
| `getTask(taskId)`, `cancel(taskId)` | `GetTask`, `CancelTask`. |
| `extendedDescriptor()` | `GetExtendedAgentCard`. `UNSUPPORTED` if the card doesn't declare one; `AUTHENTICATION` if the agent refuses this caller. |

The connector is thread-safe once started.

## The `ask` tool

As a `ToolConnector`, the client offers exactly one tool, `ask`, so a tool-calling model can reach
the agent through a `ToolRegistry`:

| | |
|---|---|
| Input | `{"message": string (required), "task_id": string, "context_id": string}` |
| Description | The agent's name and description, its skills, and a note to answer with `task_id`/`context_id` when the agent needs input. |
| Text | The reply text (message plus artifact text). For an interrupted task without text, a note naming the waiting state. |
| Other parts | Non-text parts of the reply and artifacts are kept, never dropped. |
| Structured | For task replies: `{"task_id", "context_id", "state"}`, with the state lower-case and hyphenated (`input-required`, `completed`, …). |
| Error flag | Set when the task ended `failed`, `rejected` or `canceled`. |

```java
ToolOutcome first = registry.invoke(ToolInvocation.of("travel.ask", Map.of("message", "Book a flight")));
if (first.structuredContent().orElse(null) instanceof Map<?, ?> task
        && "input-required".equals(task.get("state"))) {
    ToolOutcome second = registry.invoke(ToolInvocation.of("travel.ask", Map.of(
            "message", "To Berlin",
            "task_id", task.get("task_id"),
            "context_id", task.get("context_id"))));
}
```

## Serving an agent

```java
AgentDescriptor descriptor = new AgentDescriptor("Travel agent", "Books flights", "1.0.0",
        List.of(new AgentDescriptor.Skill("book", "Book flight", "Books a flight",
                List.of("travel"), List.of("Book me a flight to Berlin"))));

try (A2aAgentServer agent = A2aAgentServer.builder(handler, descriptor)
             .publicUrl("https://agents.example.com")      // required: advertised in the card
             .build();
     AgentWeaveServer server = AgentWeaveServer.builder().port(8080)
             .authenticator(authenticator)
             .endpoints(agent)
             .start()) {
    server.join();
}
```

The server contributes two async endpoints:

| Endpoint | Name | Purpose |
|---|---|---|
| `GET /.well-known/agent-card.json` (`A2aAgentServer.AGENT_CARD_PATH`) | `a2a-card` | The public agent card. |
| `POST /a2a` | `a2a` | JSON-RPC, with SSE for streaming methods. Requests must carry `A2A-Version: 1.0`; without it the server answers `-32009`. |

| Builder method | Default | Meaning |
|---|---|---|
| `publicUrl(String)` / `publicUrl(Supplier<String>)` | **required** | Externally reachable base URL (scheme, host, port, context path). The card's interface URL is this plus the path. A `Supplier` is resolved when the card is first needed, for hosts that bind port 0. `build()` fails with `CONFIGURATION` without it. |
| `path(path)` | `/a2a` | Exact servlet path of the JSON-RPC endpoint. |
| `endpointName(name)` | `a2a` | Endpoint name prefix; must be unique per host. |
| `streaming(boolean)` | `true` | Offer `SendStreamingMessage` and `SubscribeToTask`. |
| `pushNotifications(boolean)` | `true` | Offer push notifications. |
| `insecurePushTargets(boolean)` | `false` | Allow push webhooks over plain `http` and to loopback or private addresses. For development only. |
| `extendedDescriptor(descriptor)` | none | A richer card for `GetExtendedAgentCard`, served to authenticated callers only. |
| `inputModes(modes)`, `outputModes(modes)` | `text/plain`, `application/json` | The card's default modes; these match what `Text` and `Json` content produce. Override when your handler emits files. |
| `agentTimeout(duration)` | 30 s (at least 1 s) | How long a blocking `SendMessage` waits for the handler before answering with the task's current state. |
| `executor(executorService)` | a cached daemon pool | Runs agent handlers. |
| `observations(handler)` | none | Observes JSON-RPC methods and handler runs (`agent/execute`). |

After building: `endpoints()`, `interfaceUrl()` (the JSON-RPC URL in the card), `compliance()`, and
`close()`, which stops the server's background threads. The card declares no `securitySchemes`:
authentication is the host's `InboundAuthenticator`, which also guards the card endpoint. Admit
`GET /.well-known/agent-card.json` anonymously if the card should be public (see
[Security](../concepts/security.md#inbound-inboundauthenticator)).

### Task lifecycle

The handler API is described in [Agents](../concepts/agents.md#serving-an-agent). On the wire:

| Handler action | A2A result |
|---|---|
| `task.reply(message)` on new work | A `Message` result; no task is created. |
| The first other call (`working`, `artifact`, …) | Creates the task, with the request message in its history. |
| `working(status)` | `TASK_STATE_WORKING` status update. |
| `artifact(...)` | An artifact update. |
| `requireInput(question)` / `requireAuth(reason)` | `TASK_STATE_INPUT_REQUIRED` / `TASK_STATE_AUTH_REQUIRED`, with the text as status message. |
| `complete(...)`, `fail(...)`, `reject(...)` | `TASK_STATE_COMPLETED`, `FAILED`, `REJECTED`. |
| The handler returns without finishing or pausing | Completed. |
| The handler throws | Failed, with the exception message. |
| `CancelTask` | `isCancelled()` turns true, `onCancel(taskId)` runs, the task becomes canceled; later emissions of that run are ignored. |

A caller answers an interrupted task by sending a message with its `taskId` and `contextId`; the
handler then sees `request.resumed() == true` and the earlier messages in `request.history()`.

### Task ownership

A task belongs to the `CallerIdentity` name that created it. Only that caller can read, continue,
cancel, subscribe to, or configure push notifications for it. Everyone else, and requests for
unknown tasks, get "task not found" (`-32001`), as the specification requires for inaccessible
tasks. Anyone the authenticator admits may create tasks; **all anonymous callers share one
identity**, so use an authenticator that names callers if tasks must be isolated.

### Push notifications

Push uses the SDK's sender with its default URL policy: webhooks must be `https` and resolve to
public addresses. `insecurePushTargets(true)` lifts that for development. Streaming sends and
`CreateTaskPushNotificationConfig` register a webhook before events flow. A push config embedded in
a blocking `SendMessage` is stored after the first event, so a fast agent's earliest events aren't
pushed.

### Extended agent card

With `extendedDescriptor(...)` set, the card declares an extended card and `GetExtendedAgentCard`
returns it to non-anonymous callers. Anonymous callers get HTTP 401 with `WWW-Authenticate`, because
the specification requires authentication for it.

## Content mapping

Both directions:

| A2A part | AgentWeave content |
|---|---|
| `TextPart` | `Content.Text` |
| `FilePart` with bytes | `Content.Data` (base64 as on the wire, file name kept; a missing media type becomes `application/octet-stream`) |
| `FilePart` with a URI | `Content.Link` |
| `DataPart` | `Content.Json` |
| anything else | `Content.Unmapped` (an outgoing `Unmapped` part is rejected) |

An outgoing `Content.Resource` becomes a `FilePart` with bytes (text is UTF-8 encoded), named after
the last segment of its URI, because A2A has no inline part that keeps a URI.

## Error mapping

The native code is kept as `protocolCode`.

| SDK failure | `ErrorCategory` |
|---|---|
| HTTP 401 or 403 | `AUTHENTICATION` |
| Other HTTP status | `CONNECTION` while starting, `PROTOCOL` afterwards |
| `-32009` version not supported | `VERSION_MISMATCH` |
| `-32001` task not found, `-32602`, `-32005` | `INVALID_ARGUMENTS` (unknown and not-owned tasks are indistinguishable by design) |
| `-32601`, `-32004`, `-32003`, `-32007`, `-32008` | `UNSUPPORTED` |
| I/O failure | `CONNECTION` |
| Anything else | `PROTOCOL` |

## Coverage

Feature ids are in `A2aFeatures`. The server's streaming, push and extended-card features follow
its builder settings.

| Feature id | Client | Server |
|---|---|---|
| `binding/jsonrpc` | ✓ | ✓ |
| `discovery/agent-card` | ✓ | ✓ |
| `discovery/extended-agent-card` | ✓ | ✓ with `extendedDescriptor` |
| `operation/send-message`, `operation/get-task`, `operation/cancel-task` | ✓ | ✓ |
| `operation/send-streaming-message`, `operation/subscribe-to-task` | ✓ | ✓ with `streaming` |
| `operation/list-tasks` | — | ✓ |
| `push-notifications` | — | ✓ with `pushNotifications` |
| `tasks/multi-turn` | ✓ | ✓ |
| `content/text`, `content/file-bytes`, `content/file-uri`, `content/data` | ✓ | ✓ |
| `auth/static-credentials` | ✓ | — |
| `auth/inbound-authenticator` | — | ✓ |
| `binding/grpc`, `binding/http-json` | — | — |
| `discovery/card-signatures` | — | — |
| `auth/security-schemes` (OAuth/OIDC/mTLS flows) | — | — |
| `extensions`, `multi-tenancy` | — | — |

## Limitations

- **In-memory state.** Tasks live in memory and are lost on restart; there is no persistent store.
- **Streams stay open while a task is interrupted.** The JSON-RPC binding closes a stream only on
  terminal states. The client returns as soon as a task is `input-required` or `auth-required`,
  but the underlying SSE connection stays open until the task ends.
- **No client-side push configuration or task listing.**
- **Shutdown.** `close()` stops the SDK's event-bus thread by matching its thread name; if a
  future SDK renames it, that daemon thread would remain until JVM exit.

The server has been checked against the official Python `a2a-sdk` 1.3.0 client (card, message,
task, multi-turn and streaming exchanges).
