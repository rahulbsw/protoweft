# Spring AI example

A Spring Boot 4 application where a Spring AI `ChatClient` calls Protoweft tools and is served
as an **A2A** agent, using `protoweft-spring-ai`.

- `AssistantTools` holds plain `@AgentTool` bean methods. The Protoweft starter registers them in
  its `ToolRegistry` bean as source `local`, so the model sees `local__temperature`,
  `local__time` and `local__whoami`.
- `AssistantConfiguration` wires everything with explicit beans:
  - The `ChatClient` gets every registry tool through `ProtoweftToolCallbacks.from(registry)`.
  - A `MessageChatMemoryAdvisor` keeps one conversation per A2A context.
  - The `AgentHandler` bean is `SpringAiAgents.streamingHandler(chatClient)`. The starter serves
    it over A2A (`protoweft.server.a2a.enabled: true`), and each answer chunk is streamed as a
    `working` status.
- The model is Spring AI's OpenAI chat model, configured from the environment.

| Endpoint | Protocol |
|---|---|
| `GET /.well-known/agent-card.json`, `POST /a2a` | A2A 1.0 agent card and JSON-RPC (streaming included) |

## Run

```bash
export OPENAI_API_KEY=sk-...          # without it the app starts, but model calls fail
export OPENAI_MODEL=gpt-4o-mini       # optional; this is the default
./gradlew :examples:spring-ai-example:bootRun
```

The app listens on port 8080; use `--server.port=…` to change it. For an OpenAI-compatible
server, such as Ollama, set `spring.ai.openai.base-url`.

### More tools from an MCP server

Every client configured under `protoweft.mcp.clients` becomes a registry source, and the
`ChatClient` sees its tools on its next request (the callbacks list the registry on each request):

```bash
./gradlew :examples:spring-ai-example:bootRun \
  --args='--protoweft.mcp.clients.remote.url=http://localhost:3001'
```

The model then also sees `remote__<tool>` for each tool of that server. The URL is the server's
base URL, and the Streamable HTTP endpoint defaults to `/mcp` (`protoweft.mcp.clients.remote.endpoint`).
UTCP manuals (`protoweft.utcp.manuals`) and A2A agents (`protoweft.a2a.agents`) work the same way.

## Try it

```bash
curl -s localhost:8080/.well-known/agent-card.json

curl -s -X POST localhost:8080/a2a -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"What is the weather in Paris?"}]}}}'
```

The answer is the task's `response` artifact. Streaming clients (`SendStreamingMessage`) also
receive each answer chunk as it arrives. To keep a conversation going, send the next message with
the `contextId` the first reply returned.

Without `OPENAI_API_KEY` the task fails with the model client's error, reported as an A2A
`TASK_STATE_FAILED` status.

## Test

```bash
./gradlew :examples:spring-ai-example:test
```

`SpringAiExampleTest` runs the application with a scripted `ChatModel` bean marked `@Primary`
instead of OpenAI, so it needs no network. It talks to the application through Protoweft's A2A
client and checks three things:

- a blocking answer that uses `local__temperature`;
- a streamed answer where `local__whoami` sees the caller;
- conversation memory across two tasks in one context.
