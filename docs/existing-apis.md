# Exposing existing APIs over MCP and UTCP

Protoweft can put an API you already have in front of MCP and UTCP clients without rewriting it.
Which path fits depends on what the API is:

| You have | Path | Code |
|---|---|---|
| Java code (services, Spring beans) | Describe the operations as tools with `@AgentTool` and serve them | a thin adapter class |
| An HTTP/REST API, in any language | Describe its endpoints in a UTCP manual, then serve that over MCP | a JSON file, no Java changes to the API |
| An OpenAPI or Swagger spec | Not imported directly; see [OpenAPI and Swagger](#openapi-and-swagger) | — |
| LangChain4j or Spring AI `@Tool` methods | Re-serve them as they are | see [LangChain4j](integrations/langchain4j.md#tool-methods-out), [Spring AI](integrations/spring-ai.md#tools-out-spring-ai-tool-methods-over-mcp-and-utcp) |

## Java code

Put `@AgentTool` on methods of a small adapter that calls your existing service. The schema is
generated from the Java parameter types (see [Tools](concepts/tools.md#defining-local-tools)).

```java
public class OrderTools {
    private final OrderService orders;   // your existing service

    public OrderTools(OrderService orders) {
        this.orders = orders;
    }

    @AgentTool(description = "Look up an order by id", readOnly = true)
    public Order getOrder(@ToolParam(description = "Order id") String id) {   // Order: a record, returned as JSON
        return orders.find(id);
    }
}

LocalTools tools = LocalTools.builder().scan(new OrderTools(orderService)).build();

ProtoweftServer.builder().port(8080)
        .endpoints(McpToolServer.http(tools),                 // MCP Streamable HTTP at /mcp
                   UtcpManualProvider.builder(tools).build())  // UTCP manual at GET /utcp
        .start();
```

With Spring Boot, make the adapter a `@Component` and switch the servers on; no server code is
needed:

```yaml
protoweft:
  server:
    mcp:
      enabled: true
    utcp:
      enabled: true
```

!!! tip "Use an adapter rather than annotating the service itself"
    Protoweft calls a tool bean's **target** object, so Spring advice on the tool method itself
    (`@Transactional`, `@PreAuthorize`, caching) is bypassed
    ([Spring Boot: local tools](integrations/spring-boot.md#local-tools)). Calls the adapter makes
    *into* an injected service still go through that service's proxy, so its advice applies.

Callers are authenticated by the host's `InboundAuthenticator` or Spring Security, and a tool can
receive the `CallerIdentity` as a parameter to enforce its own rules
([Security](concepts/security.md)).

## An existing HTTP API

A UTCP manual describes how to call HTTP endpoints directly. Write one entry per operation; the
call template says how arguments map onto the request (path placeholders, query, headers, body;
see [UTCP: HTTP call templates](protocols/utcp.md#http-call-templates)):

```json
{
  "utcp_version": "1.1.0",
  "manual_version": "1.0.0",
  "tools": [{
    "name": "get_order",
    "description": "Look up an order by id",
    "inputs": {
      "type": "object",
      "properties": {"id": {"type": "string"}, "verbose": {"type": "boolean"}},
      "required": ["id"]
    },
    "tool_call_template": {
      "call_template_type": "http",
      "http_method": "GET",
      "url": "https://orders.example.com/orders/{id}",
      "auth": {"auth_type": "api_key", "api_key": "${ORDERS_KEY}", "var_name": "X-Api-Key", "location": "header"}
    }
  }]
}
```

Here `id` fills the path and `verbose` becomes a query parameter, so a call with
`{"id": "A-17", "verbose": true}` sends `GET /orders/A-17?verbose=true`.

**For UTCP clients** that's all: publish the file (for example at `/utcp` on the API's host). UTCP
clients call the API directly, and Protoweft isn't involved at run time.

**For MCP clients**, load the manual into a `ToolRegistry` and serve the registry over MCP.
Protoweft then turns each MCP `tools/call` into the HTTP request the manual describes:

```java
UtcpClientConnector orders = UtcpClientConnector.file("orders", Path.of("orders-manual.json"))
        .allowedCommunicationProtocols("http")         // a file manual may describe http tools
        .variable("orders_ORDERS_KEY", System.getenv("ORDERS_KEY"))
        .build();
orders.start();

ToolRegistry registry = new ToolRegistry().register("orders", orders);

ProtoweftServer.builder().port(8080)
        .endpoints(McpToolServer.http(registry))      // MCP clients see "orders.get_order"
        .start();
```

- **`allowedCommunicationProtocols("http")` is required** for `file` and `text` manuals that
  describe HTTP tools. Without it UTCP 1.1's rule applies (a manual's tools may only use the
  manual's own type), and every tool is skipped (`skippedTools()` says why). A manual fetched with
  `UtcpClientConnector.http(...)` allows `http` tools by default.
- **HTTPS or loopback only.** Tool URLs must be `https`, or plain `http` on `localhost` /
  `127.0.0.1`; anything else is rejected ([UTCP: security rules](protocols/utcp.md#security-rules)).
  An internal service on plain HTTP needs TLS, or a gateway on the same host.
- **Credentials** for the API come from manual variables (`${ORDERS_KEY}`, looked up as
  `orders_ORDERS_KEY`, from `variable(...)`, a `.env` file or the environment) and the manual's
  `auth` block (API key, basic or OAuth2 client credentials). MCP callers never see them.
- **You are building a proxy.** MCP callers spend the API credentials the server holds, so put an
  `InboundAuthenticator` in front ([Security](concepts/security.md)).
- The same registry can also be served with `UtcpManualProvider`. That publishes Protoweft's own
  manual, so UTCP clients then call through Protoweft (`POST /utcp/tools/orders_get_order`, with the
  arguments wrapped in `body`) instead of calling the API directly.

With Spring Boot, fetch the manual from a URL and serve the whole registry over MCP:

```yaml
protoweft:
  utcp:
    manuals:
      orders:
        url: https://orders.example.com/utcp     # the manual above, served by the API
        variables:
          ORDERS_KEY: ${ORDERS_KEY}
  server:
    mcp:
      enabled: true
      expose: registry                           # serve remote tools too, as orders.get_order
```

!!! warning "Spring Boot `file` manuals can't describe HTTP tools yet"
    `protoweft.utcp.manuals.<name>.file` has no allowed-protocols setting, so a file manual's
    `http` tools are skipped. Serve the manual at a URL (`url`), or build the connector in a
    `@Bean` with `allowedCommunicationProtocols("http")`.

## OpenAPI and Swagger

Protoweft does **not** import OpenAPI or Swagger specifications. A document with `openapi`,
`swagger` or `paths` given to a UTCP connector is rejected with `UNSUPPORTED`, and the compliance
report lists `manual/openapi-conversion` as unsupported ([UTCP: coverage](protocols/utcp.md#coverage)).

Until it is, either write the manual by hand as above, one entry per operation, or generate one
with the UTCP reference implementation's converter (`OpenApiConverter` in python-utcp's `utcp-http`
plugin, for OpenAPI 2.0 and 3.0) and save the result as JSON. Loading a python-utcp-generated
manual hasn't been tested with Protoweft.
