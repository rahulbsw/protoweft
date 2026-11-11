# AG-UI web example

A Spring AI agent served over [AG-UI](https://docs.ag-ui.com) with `protoweft-agui`, and a small
browser page that talks to it with the official `@ag-ui/client`.

- **The agent:** `WebAgentConfiguration` builds a `ChatClient` with the application's Protoweft
  tools (`WebTools.temperature`) and no chat-memory advisor, since AG-UI clients send the whole
  conversation with every run. The starter serves it at `POST /agui`
  (`protoweft.server.agui.enabled: true`), with its capabilities at `GET /agui/capabilities`.
- **The page** (`src/main/resources/static/`, script in `frontend/src/app.js`) shows:
  - streamed replies;
  - the agent's tool calls, as cards;
  - the shared state.

  It also offers the agent its own tool, `set_theme`. When the agent calls it, the page recolours
  itself and sends the result on the next run.

## Run it

```bash
# 1. Build the page script (Node 20+). The bundle goes to src/main/resources/static/app.js.
cd examples/agui-web/frontend && npm ci --ignore-scripts && npm run build && cd -

# 2. Start the agent with a model (any OpenAI-compatible server works; see application.yml).
OPENAI_API_KEY=sk-... ./gradlew :examples:agui-web:bootRun
```

Open http://localhost:8080 and try “What is the temperature in Oslo?” or “Make the page teal”.

## From CopilotKit

CopilotKit reaches AG-UI agents through its runtime. Register this agent as an `HttpAgent` with
`url: "http://localhost:8080/agui"` (see [the AG-UI guide](../../docs/protocols/agui.md)). A page
served from another origin also needs that origin in `protoweft.server.agui.allowed-origins`.

## Tests

`AgUiWebExampleTest` runs the application with a scripted chat model (no network) and drives it
over AG-UI with Protoweft's own client. It checks a Protoweft tool call shown in the stream, the
frontend tool round trip, and the served page and capabilities.
