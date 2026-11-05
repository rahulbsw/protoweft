# Spring Boot example

A Spring Boot 4 application where one set of plain bean methods (`WeatherTools`, annotated
`@AgentTool`) is served over **MCP** and **UTCP**, and an `AgentHandler` bean (`WeatherAgent`) is
served over **A2A**. All the wiring comes from `agentweave-spring-boot-starter` and
`application.yml`. Every AgentWeave endpoint requires `Authorization: Bearer demo-token`
(`TokenAuthenticator`), except the public A2A agent card.

| Endpoint | Protocol |
|---|---|
| `POST /mcp` | MCP Streamable HTTP |
| `GET /utcp`, `POST /utcp/tools/<tool>` | UTCP 1.1 manual and tool calls |
| `GET /.well-known/agent-card.json`, `POST /a2a` | A2A 1.0 agent card and JSON-RPC |
| `GET /actuator/health`, `GET /actuator/info` | connector health and compliance reports |

## Run

```bash
./gradlew :examples:spring-boot-example:bootRun
# or
./gradlew :examples:spring-boot-example:bootJar
java -jar examples/spring-boot-example/build/libs/spring-boot-example-0.1.0-SNAPSHOT.jar
```

The app listens on port 8080. Use `--server.port=…` to change the port and
`EXAMPLE_API_TOKEN=…` to change the token.

## Try it

**MCP**, with the MCP Inspector CLI:

```bash
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer demo-token" --method tools/list
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer demo-token" --method tools/call \
  --tool-name forecast --tool-arg city=Paris --tool-arg days=2
```

For the interactive Inspector UI, run `npx @modelcontextprotocol/inspector` and connect with
transport "Streamable HTTP", URL `http://localhost:8080/mcp` and header
`Authorization: Bearer demo-token`.

**MCP**, with curl. `initialize` returns an `Mcp-Session-Id` header; send it on the following
requests:

```bash
curl -i -X POST localhost:8080/mcp -H 'Authorization: Bearer demo-token' \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
SID=...   # the Mcp-Session-Id from the response
curl -X POST localhost:8080/mcp -H 'Authorization: Bearer demo-token' -H "Mcp-Session-Id: $SID" \
  -H 'MCP-Protocol-Version: 2025-11-25' -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
curl -X POST localhost:8080/mcp -H 'Authorization: Bearer demo-token' -H "Mcp-Session-Id: $SID" \
  -H 'MCP-Protocol-Version: 2025-11-25' -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"temperature","arguments":{"city":"Paris"}}}'
```

**UTCP.** The manual lists each tool with its HTTP call template. A tool call's request body is
the arguments object:

```bash
curl -H 'Authorization: Bearer demo-token' localhost:8080/utcp
curl -X POST -H 'Authorization: Bearer demo-token' -H 'Content-Type: application/json' \
  -d '{"city":"Paris","days":2}' localhost:8080/utcp/tools/forecast
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

If the message names no city, the agent asks for one: the task is returned in state
`TASK_STATE_INPUT_REQUIRED`. Answer with a message that carries the returned `taskId` and
`contextId`.

## Consuming other services

Uncomment the `agentweave.mcp.clients`, `agentweave.utcp.manuals` or `agentweave.a2a.agents`
entries in `application.yml`. Each declared connector is started with the application, and its
tools are registered in the `ToolRegistry` bean as `<name>.<tool>`. A remote A2A agent appears as
`<name>.ask`. You can then inject the `ToolRegistry` bean anywhere, or hand it to LangChain4j
through `AgentWeaveToolProvider`. Set `agentweave.server.mcp.expose=registry` to re-serve all of
those tools over MCP.

## Test

```bash
./gradlew :examples:spring-boot-example:test
```

`SpringBootExampleTest` starts the application on a random port. It calls the application through
each protocol with AgentWeave's own clients (`McpClientConnector`, `UtcpClientConnector`,
`A2aAgentConnector`), including an A2A follow-up question, and checks the 401 responses and the
health endpoint.
