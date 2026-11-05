# ARGI example

A Spring Boot 4 application where an [ARGI](https://github.com/agentic-ai-java/argi) 2.1.0-RC1
`ReactAgent` calls AgentWeave tools, chosen per request by the capability catalog, and is served as
an **A2A** agent. Transfers wait for the caller's approval, which A2A carries as `input-required`.
It uses `agentweave-argi`.

- `BankTools` holds plain `@AgentTool` bean methods. The AgentWeave starter registers them in its
  `ToolRegistry` bean as source `local`, so the model sees `local__balance`, `local__rate` and
  `local__transfer`.
- `agentweave.discovery.enabled: true` makes the starter build a `CapabilityCatalog` bean over the
  registry.
- `AgentConfiguration` wires the agent with explicit beans:
  - `CatalogToolSelectionInterceptor` registers every catalog capability on the agent and, on each
    model call, offers only the ones the catalog finds for the latest user message (up to 3) and
    the caller may see. No extra LLM call is made.
  - `HumanInTheLoopHook.approvalOn("local__transfer", ...)` pauses the run before a transfer. The
    `MemorySaver` keeps the paused run.
  - The `AgentHandler` bean is `ArgiAgents.handler(agent)`. The starter serves it over A2A
    (`agentweave.server.a2a.enabled: true`).
- The model is Spring AI's OpenAI chat model, configured from the environment.

| Endpoint | Protocol |
|---|---|
| `GET /.well-known/agent-card.json`, `POST /a2a` | A2A 1.0 agent card and JSON-RPC |

## Run

```bash
export OPENAI_API_KEY=sk-...          # without it the app starts, but model calls fail
export OPENAI_MODEL=gpt-4o-mini       # optional; this is the default
./gradlew :examples:argi-example:bootRun
```

The app listens on port 8080; use `--server.port=…` to change it. For an OpenAI-compatible
server, such as Ollama, set `spring.ai.openai.base-url`.

## Try it

Ask for a transfer:

```bash
curl -s -X POST localhost:8080/a2a -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"Transfer 25 euros to bob"}]}}}'
```

The task comes back as `TASK_STATE_INPUT_REQUIRED`. Its status message lists the pending call:
the tool name, its tool-call id, the arguments and the approval description. Answer on the same
task with the `taskId` and `contextId` from that response:

```bash
curl -s -X POST localhost:8080/a2a -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{"message":{"messageId":"m2","role":"ROLE_USER","taskId":"<taskId>","contextId":"<contextId>","parts":[{"text":"approve"}]}}}'
```

You can answer in three ways:

- `approve` (or `yes`) runs the transfer;
- `reject <reason>` (or `no`) skips it, and the model is told the reason;
- a data part edits the arguments:
  `{"data":{"decision":"edited","id":"<tool call id>","arguments":{"to":"bob","amount":10}}}`.

When several calls are pending, send one decision per `id`. The task asks again until every call
is decided. The answer is the task's `response` artifact.

## Test

```bash
./gradlew :examples:argi-example:test
```

`ArgiExampleTest` runs the application with a scripted `ChatModel` bean marked `@Primary`
instead of OpenAI, so it needs no network. It talks to the application through AgentWeave's A2A
client and checks three things:

- a balance question offers `local__balance` but not `local__transfer`, and the answer uses it;
- a transfer pauses as `INPUT_REQUIRED`, then `approve` on the same task runs it and completes;
- a rejected transfer never runs, and the model sees the reason.
