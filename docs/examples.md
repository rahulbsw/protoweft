# Examples

The repository has three runnable examples under `examples/`. Run them from the repository root
with the Gradle wrapper; they need only a JDK (17 or newer). None of them needs network access or
API keys, except the optional runs against Node-based reference servers.

## MCP client

`examples/mcp-client-example` connects to an MCP server over stdio and uses it only through the
protocol-neutral `ToolConnector` API. It prints the compliance report, lists the tools, and shows
the observability hook.

```bash
./gradlew :examples:mcp-client-example:run
```

With no arguments it launches a bundled one-tool Java MCP server (`DemoMcpServer`, built directly
on the MCP SDK) and calls its `greet` tool:

```text
  [observe] initialize /path/to/java ok in 349 ms
Protocol:          mcp
SDK:               io.modelcontextprotocol.sdk:mcp 2.1.0
Wire revisions:    [2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25]
Negotiated:        2025-11-25
Supported:         [content/audio, content/image, content/resource-link, content/text, lifecycle/version-negotiation, tools/call, tools/list, tools/structured-output, transport/stdio]
Not implemented:   [authorization, cancellation, completion, content/resource, elicitation, logging, progress, prompts, resources, roots, sampling, tools/list-changed, transport/sse, transport/streamable-http]
Tools:
  [observe] tools/list  ok in 16 ms
  - greet: Greets someone by name
  [observe] tools/call greet ok in 24 ms
greet -> Hello, AgentWeave!
```

Pass a server command line to use another server, for example the official reference server
(needs Node.js). The example then lists its tools:

```bash
./gradlew :examples:mcp-client-example:run --args="npx -y @modelcontextprotocol/server-everything"
```

## Plain-Java gateway

`examples/plain-java-gateway` defines one tool and one agent in plain Java, serves them over MCP,
UTCP and A2A from a single embedded Jetty server on a free local port, then consumes all three back
through one `ToolRegistry`.

```bash
./gradlew :examples:plain-java-gateway:run
```

```text
Serving MCP at http://127.0.0.1:50684/mcp, UTCP at http://127.0.0.1:50684/utcp, A2A at http://127.0.0.1:50684

One registry, three protocols:
  - via_mcp.forecast: Weather forecast for a city
  - via_utcp.forecast: Weather forecast for a city
  - via_a2a.ask: Send a message to the remote A2A agent 'Weather agent': Answers weather questions by city

Calls:
  via_mcp.forecast   -> {"city":"Oslo","celsius":21.5,"summary":"Sunny"}
  via_utcp.forecast  -> {"city":"Oslo","celsius":21.5,"summary":"Sunny"}
  via_a2a.ask        -> {"city":"Oslo","celsius":21.5,"summary":"Sunny"}

Compliance:
  mcp   sdk=io.modelcontextprotocol.sdk:mcp 2.1.0  wire=[2025-03-26, 2025-06-18, 2025-11-25] negotiated=2025-11-25
  utcp  sdk=none 0.1.0-SNAPSHOT  wire=[1.0, 1.1] negotiated=1.1.0
  a2a   sdk=org.a2aproject.sdk:a2a-java-sdk-client 1.5.0.Final  wire=[1.0] negotiated=1.0
```

The port differs on every run. Things the output shows:

- The same `forecast` method answers over all three protocols.
- The UTCP call passes its arguments as `{"body": {"city": "Oslo"}}`, because AgentWeave's UTCP
  provider [publishes the argument object as the JSON body](protocols/utcp.md#manual-shape).
- The A2A agent appears as one `ask` tool.
- MCP over Streamable HTTP lists only the revisions that have that transport.

The program exits when it's done; closing the registry and the server releases every thread.

## Spring Boot

`examples/spring-boot-example` is a Spring Boot 4 application. One set of `@AgentTool` bean methods
(`WeatherTools`) is served over MCP and UTCP, and an `AgentHandler` bean (`WeatherAgent`) over A2A.
All the wiring comes from the starter and `application.yml`. Every AgentWeave endpoint requires
`Authorization: Bearer demo-token` (`TokenAuthenticator`), except the public A2A agent card.

```bash
./gradlew :examples:spring-boot-example:bootRun
```

The application listens on port 8080. Use `--server.port=…` to change the port (with `bootRun`:
`--args='--server.port=9090'`) and the `EXAMPLE_API_TOKEN` environment variable to change the token.

| Endpoint | Protocol |
|---|---|
| `POST /mcp` | MCP Streamable HTTP |
| `GET /utcp`, `POST /utcp/tools/<tool>` | UTCP manual and tool calls |
| `GET /.well-known/agent-card.json`, `POST /a2a` | A2A agent card and JSON-RPC |
| `GET /actuator/health`, `GET /actuator/info` | Connector health and compliance reports |

### Try it

**UTCP:**

```bash
curl -H 'Authorization: Bearer demo-token' localhost:8080/utcp
curl -X POST -H 'Authorization: Bearer demo-token' -H 'Content-Type: application/json' \
  -d '{"city":"Paris","days":2}' localhost:8080/utcp/tools/forecast
# [{"day":0,"celsius":14.0,"sky":"sunny"},{"day":1,"celsius":15.0,"sky":"cloudy"}]
curl -X POST -H 'Authorization: Bearer demo-token' -H 'Content-Type: application/json' \
  -d '{"city":"Paris","days":9}' localhost:8080/utcp/tools/forecast
# HTTP 500 {"error":"days must be between 1 and 7"}
curl localhost:8080/utcp
# HTTP 401 {"error":"bearer token required"}
```

The manual tells UTCP clients to send the `Authorization` header from their `WEATHER_TOKEN`
variable. For a client that registers the manual as `weather`, that variable is
`weather_WEATHER_TOKEN=Bearer demo-token`.

**A2A:**

```bash
curl localhost:8080/.well-known/agent-card.json
curl -X POST localhost:8080/a2a -H 'Authorization: Bearer demo-token' \
  -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"What is the weather in Paris?"}]}}}'
```

A question naming a city gets a direct message (`"It is 15.0 °C in Paris."`). If the message
names no city, the agent asks for one: the result is a task in state `TASK_STATE_INPUT_REQUIRED`
with the status message "Which city?". Answer with a message that carries the returned `taskId`
and `contextId`.

The card advertises `http://localhost:8080/a2a`, because `public-url` isn't set; the application
logs a WARN about it.

**MCP**, with the MCP Inspector CLI (needs Node.js):

```bash
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer demo-token" --method tools/list
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer demo-token" --method tools/call \
  --tool-name forecast --tool-arg city=Paris --tool-arg days=2
```

The `whoami` tool returns the caller name set by `TokenAuthenticator` (`demo-client`). The
example's README also shows the raw MCP exchange with curl.

**Actuator:** `curl localhost:8080/actuator/health` includes `"agentweave":{"status":"UP"}`. The
example declares no client connectors, so `/actuator/info` shows `{"agentweave":{"connectors":{}}}`
until you uncomment the `agentweave.mcp.clients`, `agentweave.utcp.manuals` or
`agentweave.a2a.agents` entries in `application.yml`.

### Test

```bash
./gradlew :examples:spring-boot-example:test
```

`SpringBootExampleTest` starts the application on a random port and calls it through each
protocol with AgentWeave's own clients, including an A2A follow-up question, and checks the 401
responses and the health endpoint.
