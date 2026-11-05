# UTCP

`agentweave-utcp` speaks the [Universal Tool Calling Protocol](https://www.utcp.io) **1.1** in both
roles:

- **Client:** `UtcpClientConnector` registers one UTCP manual and exposes its tools through the
  core `ToolConnector` API.
- **Provider:** `UtcpManualProvider` serves any `ToolSource` as a manual plus HTTP tool endpoints.

UTCP has no official Java SDK, so both are implemented from the specification, with the Python
reference client (python-utcp core `1.2.0`, `utcp-http` `1.1.15`) as the authority where the
specification is vague or disagrees with it. Differences are listed under [Deviations](#deviations).

## Concepts

A UTCP **manual** is a JSON document listing tools. Each tool carries a **call template** that tells
the client how to call it directly, for example an HTTP method and URL, without a wrapper server. A
**manual call template** tells the client where to get the manual.

AgentWeave uses **one connector per manual**. Register the connector in a `ToolRegistry` under the
manual's name: tools are listed unprefixed, so the registry yields `<manual>.<tool>`, the same
namespacing UTCP applies.

## Client

```java
UtcpClientConnector weather = UtcpClientConnector.http("weather", "https://api.example.com/utcp")
        .variable("weather_API_KEY", System.getenv("WEATHER_KEY"))   // ${API_KEY} in manual "weather"
        .build();

try (ToolRegistry registry = new ToolRegistry().register("weather", weather)) {
    weather.start();                                              // fetches and registers the manual
    ToolOutcome out = registry.invoke(ToolInvocation.of("weather.get_forecast", Map.of("city", "Oslo")));
    System.out.println(out.error() ? "failed: " + out.text() : out.text());
}
```

### Where the manual comes from

| Factory | Manual call template | Tools allowed by default |
|---|---|---|
| `http(name, url)` | `{"call_template_type": "http", "url": …}`, fetched with `GET` | `http` |
| `text(name, json)` | `{"call_template_type": "text", "content": …}`, given inline | `text` only |
| `file(name, path)` | `{"call_template_type": "file", "file_path": …}`, a local JSON file | `file` only (not callable) |
| `fromCallTemplate(map)` | any raw manual call template (an entry of a UTCP client's `manual_call_templates`); needs `name` and `call_template_type` | the template's own type |

As UTCP 1.1 specifies, a manual's tools may use only the manual's own call-template type unless
`allowed_communication_protocols` says otherwise. **Text and file manuals that describe `http` tools
therefore need `allowedCommunicationProtocols("http")`:**

```java
UtcpClientConnector local = UtcpClientConnector.file("local_api", Path.of("manual.json"))
        .allowedCommunicationProtocols("http")
        .build();
```

Tools whose type isn't allowed are dropped at registration with a warning; calling one fails with
`TOOL_NOT_FOUND` and names the reason.

### Builder options

| Method | Default | Meaning |
|---|---|---|
| `httpMethod(method)` | `GET` | Discovery method (http manuals only). |
| `header(name, value)` | none | A static header on the discovery request (http manuals only). |
| `auth(map)` | none | The UTCP `auth` object of the manual call template (http manuals only). |
| `allowedCommunicationProtocols(types...)` | the manual's own type | Which tool call-template types may be registered. |
| `variable(key, value)`, `variables(map)` | none | Configured variables; keys are full, namespaced lookup keys. |
| `dotenv(path)` | none | A `.env` file, consulted after configured variables, in the order added. |
| `environment(lookup)` | `System::getenv` | The last variable source; `k -> null` disables it. |
| `credentials(provider)` | none | Credentials for the discovery request of an http manual. |
| `observationHandler(handler)` | none | Observability hook. Note the method name, which differs from the other builders' `observations`. |
| `discoveryTimeout(duration)` | 10 s | Manual discovery timeout. |
| `callTimeout(duration)` | 30 s | Tool call and OAuth2 token timeout. |
| `httpClient(client)` | a JDK client, HTTP/1.1 | A custom `java.net.http.HttpClient`. It must use `HttpClient.Redirect.NEVER`: every redirect is re-validated by the connector. |

The manual name is sanitized as the reference client does: every non-word character becomes `_`
(`manualName()` returns the result).

### Registration and use

`start()` follows the reference `register_manual`: it substitutes variables in the manual call
template, fetches and validates the manual, and filters its tools. A fetched document without
`utcp_version` and `tools` is rejected with `UNSUPPORTED` (OpenAPI conversion isn't implemented),
as are YAML manuals.

| Method | Behavior |
|---|---|
| `listTools()` | The registered tools, unprefixed, with UTCP tags as `META_TAGS`. Null-valued schema keywords are dropped. |
| `invoke(invocation)` | Substitutes variables in the tool's call template, then calls it. |
| `search(query)` / `search(query, limit, anyOfTagsRequired)` | UTCP `search_tools` with the `tag_and_description_word_match` strategy: +3 per tag found in the query, +1 per description word longer than two characters. Default limit 10. |
| `skippedTools()` | Tools that weren't registered, with the reason (not allowed, or a call-template type that isn't implemented). |
| `compliance()` | After `start()`, carries the manual's declared `utcp_version` as the negotiated revision. |
| `close()` | Deregisters the manual. Nothing is sent on the wire. |

Allowed tools of a type that isn't implemented (`cli`, `sse`, `streamable_http`, `mcp`,
`websocket`, …) are skipped, listed in `skippedTools()`, fail with `UNSUPPORTED` when called, and
are declared unsupported in the compliance report.

## HTTP call templates

An `http` tool call template looks like this:

```json
{
  "call_template_type": "http",
  "http_method": "POST",
  "url": "https://api.example.com/forecast/{city}",
  "content_type": "application/json",
  "header_fields": ["X-Request-Id"],
  "body_field": "body",
  "headers": {"Accept": "application/json"},
  "auth": {"auth_type": "api_key", "api_key": "${API_KEY}", "var_name": "X-Api-Key", "location": "header"}
}
```

Arguments are placed in the order the reference client uses:

1. **Headers:** each argument named in `header_fields` becomes a request header.
2. **Body:** the argument named by `body_field` (default `body`) becomes the request body.
3. **Path:** each `{name}` placeholder in the URL is replaced with the percent-encoded argument.
   A missing path argument fails with `INVALID_ARGUMENTS`.
4. **Query:** every remaining argument becomes a query parameter. `null` values are omitted and
   lists repeat the key.

Non-string values in headers, path and query are sent as JSON text (`true`, `{"a":1}`). The body
is serialized as JSON when the content type is `application/json` (the default); otherwise a string
is sent as-is, an object is form-encoded, and anything else is sent as JSON text.

| Response | Outcome |
|---|---|
| `application/json` with an object | its JSON as text, plus the object as structured content |
| `application/json` with another value | its JSON as text |
| any other content type | the body as text |
| HTTP status 400 or higher | a failure outcome (`error() == true`) whose text is `"<reason>: <detail>"` |

The detail is the first non-blank `error`, `message` or `detail` field of a JSON error body, else the
body itself, with control characters collapsed and cut at 2000 characters. The reason phrase comes
from RFC 9110.

`text` tool call templates return their `content`, after variable substitution.

## Authentication

A call template's `auth` object says how to authenticate. Values are usually variables.

| `auth_type` | Fields | Sent as |
|---|---|---|
| `api_key` | `api_key` (required), `var_name` (default `X-Api-Key`), `location`: `header` (default), `query` or `cookie` | a header, query parameter or cookie named `var_name` |
| `basic` | `username`, `password` | `Authorization: Basic …` (UTF-8) |
| `oauth2` | `token_url`, `client_id`, `client_secret`, `scope` (optional) | `Authorization: Bearer <token>` from the client-credentials grant |

OAuth2 tokens are cached per token URL, client and scope. The client sends the credentials in the
form body first and falls back to HTTP Basic; if both fail, the call fails with `AUTHENTICATION`.
Tokens are refreshed 5 seconds before `expires_in`; a token without `expires_in` is cached for the
connector's lifetime.

Your `CredentialProvider` applies **only to the discovery request of an http manual**:
`BearerToken` becomes `Authorization: Bearer <token>`, and `NamedSecret` a header of that name. They
override the static headers and are overridden by the manual call template's `auth`. Tool calls
never see them; each tool authenticates through its own `auth` object, as UTCP intends.

## Variables

Call templates reference variables as `${NAME}` or `$NAME`. Variables are **namespaced by the
manual name**: the sanitized manual name, with every `_` doubled, then `_`, then the variable name.

| Manual name | Reference | Lookup key |
|---|---|---|
| `weather` | `${API_KEY}` | `weather_API_KEY` |
| `my_api` | `${API_KEY}` | `my__api_API_KEY` |
| `my-api` (sanitized to `my_api`) | `${API_KEY}` | `my__api_API_KEY` |

There is no fallback to the bare name. Keys are looked up in configured variables, then dotenv
files, then the environment. A missing variable fails with `CONFIGURATION`. Configured variable
values are themselves substituted (without a namespace) from the dotenv files and the environment.

- Variables in the manual call template are substituted at `start()`; variables in a tool's call
  template on every call.
- A string containing `$ref` is left untouched, so JSON Schema references survive.
- Dotenv files support `KEY=VALUE`, `export`, comments and quotes, without `${}` interpolation or
  multi-line values. A missing file counts as empty.

!!! warning "Inline manuals with `$schema`"
    Like the reference client, the `text` manual's `content` is variable-substituted, so an inline
    manual containing `"$schema"` fails registration with a missing-variable error. Use a `file`
    or `http` manual for such content.

## Security rules

The connector applies the reference client's HTTP trust boundary (`_security.py`). There is no
switch to turn it off.

- **HTTPS or literal loopback only**, for discovery, tool calls and OAuth2 token URLs. Loopback
  means `localhost`, `127.0.0.0/8`, `::1`, and the wildcard, IPv4-mapped and `inet_aton` spellings
  of those, decided without DNS. Plain `http` to any other host is rejected: `CONFIGURATION` for
  discovery, `PROTOCOL` for a tool URL.
- **No loopback from remote manuals.** A manual fetched from a non-loopback origin may not declare
  loopback tool or token URLs (`PROTOCOL`).
- **No argument-chosen loopback.** A request may target loopback only when the call template names
  the loopback host literally, not through a `{placeholder}` (`INVALID_ARGUMENTS`).
- **Redirects** (at most 5) are re-validated hop by hop and never followed from a remote origin into
  loopback. Across origins, authentication headers, the query and the body are dropped.

To develop against a local provider, serve it on `localhost` or `127.0.0.1`; to reach any other
host, use HTTPS.

## Errors

| Situation | Result |
|---|---|
| Tool answers HTTP ≥ 400 | failure outcome, `"<reason>: <detail>"` |
| Missing variable, bad local configuration, insecure discovery URL | `CONFIGURATION` |
| Invalid fetched manual or call template, insecure tool URL, bad redirect | `PROTOCOL` |
| Discovery answered 401/403, OAuth2 token failure, empty API key | `AUTHENTICATION` |
| Missing path argument, argument-chosen loopback host | `INVALID_ARGUMENTS` |
| Unknown tool, or a tool dropped by `allowed_communication_protocols` | `TOOL_NOT_FOUND` |
| Tool with an unimplemented call-template type, YAML or OpenAPI input | `UNSUPPORTED` |
| I/O failure | `CONNECTION` |
| Discovery over 10 s or call over 30 s (by default) | `TIMEOUT` |

## Serving tools as a UTCP manual

`UtcpManualProvider` publishes any `ToolSource`. It's a `ServerEndpointProvider`, so any AgentWeave
host mounts it, with the host's `InboundAuthenticator` in front.

```java
UtcpManualProvider utcp = UtcpManualProvider.builder(tools)
        .baseUrl("https://api.example.com")
        .manualVersion("1.2.0")
        .tags("forecast", "weather", "forecast")
        .apiKeyAuth("X-Api-Key", "WEATHER_KEY")
        .build();

AgentWeaveServer.builder().port(8080).endpoints(utcp).start();
```

| Method | Default | Meaning |
|---|---|---|
| `baseUrl(String)` / `baseUrl(Supplier<String>)` | derived from each request | The public base URL used in tool URLs. A URL containing `$` is refused, because clients would substitute it. |
| `manualVersion(version)` | `1.0.0` | The manual's `manual_version`. |
| `tags(tool, tags...)` | none | Search tags for a tool, by its `ToolSource` name. |
| `apiKeyAuth(header, variable)` | none | Announce API-key authentication on every tool (see below). |
| `toolAuth(map)` | none | A raw UTCP `auth` object for every tool call template. |

`manual(base)` returns the manual as plain maps, for inspection or tests.

### Endpoints

| Request | Response |
|---|---|
| `GET /utcp` | The manual: `{"utcp_version": "1.1.0", "manual_version": …, "tools": […]}` |
| `POST /utcp/tools/<tool>` | `200` with structured content as JSON, a text-only result as `text/plain`, other parts as a JSON array |

Failures are always `{"error": "…"}`, which reference clients surface as `"<reason>: <error>"`:

| Status | Meaning |
|---|---|
| 400 | Bad body or arguments (`INVALID_ARGUMENTS`) |
| 404 | Unknown tool |
| 500 | The tool reported a failure |
| 501 | `UNSUPPORTED` |
| 502 | `CONNECTION` (for example a re-served remote tool is unreachable) |
| 504 | `TIMEOUT` |

The manual is built per request, so a `ToolRegistry` source is always current.

### Manual shape

Each tool is published with an HTTP call template that sends the whole argument object as the JSON
body. Its `inputs` wrap the original schema as the required `body` property:

```json
{
  "name": "temperature",
  "description": "Current temperature in a city, in degrees Celsius",
  "inputs": {
    "type": "object",
    "properties": {
      "body": {
        "type": "object",
        "properties": {"city": {"type": "string", "description": "City name"}},
        "required": ["city"]
      }
    },
    "required": ["body"]
  },
  "outputs": {},
  "tags": [],
  "tool_call_template": {
    "call_template_type": "http",
    "http_method": "POST",
    "url": "https://api.example.com/utcp/tools/temperature",
    "content_type": "application/json",
    "body_field": "body"
  }
}
```

Why the wrapper: with plain reference semantics, arguments would travel in the query string as
strings. With `body_field: "body"` the whole object travels as the JSON body and keeps its types.
A UTCP client therefore calls the tool with arguments `{"body": {"city": "Oslo"}}`; on the wire the
HTTP body is `{"city": "Oslo"}`. Root `$defs`/`definitions` are hoisted to the wrapper so `$ref`s
still resolve.

Tool names are made URL-safe: every character outside `[A-Za-z0-9_-]` becomes `_`, so a registry
tool `local.forecast` publishes as `local_forecast`. Two names that collide after this fail the
manual request.

### Announcing authentication

`apiKeyAuth(header, VAR)` puts this `auth` object on every tool call template:

```json
{"auth_type": "api_key", "api_key": "${VAR}", "var_name": "<header>", "location": "header"}
```

A UTCP client resolves `${VAR}` in its own namespace: a client that registers the manual as
`weather` looks up `weather_VAR`. Your `InboundAuthenticator` then checks the header. For a bearer
token in `Authorization`, the variable's value is the whole header value, for example
`Bearer demo-token`. `toolAuth(map)` publishes any other UTCP `auth` object; every `$name` in it is
a client-side variable.

The provider has no observations hook and publishes no `ComplianceReport`.

## Deviations

**Specification documents vs. the reference client** (AgentWeave follows the reference):

- The documents' examples carry `utcp_version: "1.0.1"` and an `info` block; the reference ignores
  `info` and never checks `utcp_version`. So does AgentWeave.
- The documents order argument placement path → body → header → query; the reference applies
  `header_fields`, then `body_field`, then path, then query. Only overlapping names are affected.

**Deliberate differences from the reference client:**

- OAuth2 honors `expires_in`. When both the body and the Basic attempt fail, AgentWeave raises
  `AUTHENTICATION` instead of sending `Bearer None`, and an unset `scope` is omitted instead of
  posted as `scope=None`.
- Tools of unknown or unimplemented template types are skipped and declared, instead of rejecting
  the whole manual.
- Non-string values in headers and path become JSON text instead of Python `str()` (`True`,
  `{'a': 1}`). In the query, `null` is omitted, lists repeat the key, and other values become JSON
  text where aiohttp raises a `TypeError`.
- The reason phrase comes from RFC 9110, because `java.net.http` doesn't expose the server's.
- A repeated `{name}` placeholder is filled everywhere; the reference fails on the second one.
  The `Content-Type` check is case-insensitive, and Basic credentials are UTF-8 (aiohttp uses
  latin-1).
- `search` returns `ToolSpec`s with unprefixed names, and listed schemas drop null-valued keywords.

## Coverage

Feature ids are in `UtcpFeatures`.

| Supported | Not implemented |
|---|---|
| `manual/http`, `manual/text`, `manual/file` | `manual/yaml`, `manual/openapi-conversion` |
| `manual/allowed-communication-protocols` | `call-template/file`, `call-template/sse`, `call-template/streamable_http`, `call-template/cli`, `call-template/websocket`, `call-template/mcp`, `call-template/graphql`, `call-template/tcp`, `call-template/udp` |
| `call-template/http`, `call-template/text` | `auth/auth_tools` (only applies to OpenAPI) |
| `auth/api_key` (header, query, cookie), `auth/basic`, `auth/oauth2` (client credentials) | `search/in_mem_embeddings` |
| `variables/substitution`, `variables/dotenv-loader` | `client/call-tool-streaming` |
| `search/tag_and_description_word_match` | `client/post-processing` |
| `security/https-or-loopback` | |

The provider and client have been checked against the Python reference client: it registered an
AgentWeave manual with API-key authentication and called its tools with matching results.
