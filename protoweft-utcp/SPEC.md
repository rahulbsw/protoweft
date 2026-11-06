---
id: protoweft-utcp
type: module-design
status: draft
title: protoweft-utcp — UTCP client and manual provider (from spec 1.1)
parent: protoweft-architecture
---

# protoweft-utcp

## Responsibility

Speaks UTCP 1.1 in both roles. **Client:** `UtcpClientConnector` registers one UTCP manual and
exposes its tools through the core `ToolConnector` API. **Provider:** `UtcpManualProvider` serves
any `ToolSource` as a manual plus HTTP tool endpoints for any servlet host. There is no official
Java SDK, so both are implemented from the specification, with the Python reference client as the
authority wherever the spec docs are vague or disagree.

## Boundary

- Public API: `UtcpClientConnector` (+ `Builder`), `UtcpManualProvider` (+ `Builder`),
  `UtcpFeatures`. Everything else is package-private; no Jackson or servlet type in a public
  signature.
- Depends on `protoweft-core`, `protoweft-server` (the `ServerEndpointProvider` contract),
  Jackson 3 (`implementation`), servlet API (`compileOnly`). HTTP is `java.net.http`.

## Decisions

- **One connector per manual.** The manual name is the name the app registers the connector
  under in a `ToolRegistry`; tools are listed unprefixed, so the registry yields
  `<manual>.<tool>`, the same namespacing UTCP applies. The name is sanitized like the reference
  (`[^\w]` → `_`) and that form namespaces variables (`${API_KEY}` in `my_api` →
  `my__api_API_KEY`, no bare fallback; lookup: configured variables → dotenv files →
  environment). The reference's duplicate-manual error is `ToolRegistry.register`'s rejection;
  deregistering is `close()`.
- **Registration follows the reference `register_manual`.** Variables are substituted in the
  manual call template at `start()` and in a tool's call template at every call. Tools whose
  `call_template_type` is not in `allowed_communication_protocols` (unset/empty ⇒ only the
  manual's own type) are dropped with a warning; calling one is `TOOL_NOT_FOUND` with the reason.
  Text and file manuals therefore need `allowedCommunicationProtocols("http")` for http tools.
- **Coverage is declared, never guessed.** Manual templates `http`, `text`, `file`; tool templates
  `http` and `text`. Allowed tools of other types (cli, sse, streamable_http, mcp, websocket, …) are
  skipped at registration, listed in `skippedTools()`, fail with `UNSUPPORTED` when called, and are
  `unsupported` in the `ComplianceReport`. YAML and OpenAPI inputs fail with `UNSUPPORTED`.
- **Errors.** HTTP status ≥ 400 from a tool is a tool failure (`ToolOutcome.error()`), text in the
  reference format `"<reason>: <detail>"` (detail = first non-blank `error`/`message`/`detail`,
  else the body; control characters collapsed; 2000 chars). Everything else is an
  `ProtoweftException`: missing variable / bad local config → `CONFIGURATION`; invalid fetched
  manual or call template, insecure tool URL, bad redirect → `PROTOCOL`; discovery 401/403 and
  token failures → `AUTHENTICATION`; missing path parameter, argument-chosen loopback host →
  `INVALID_ARGUMENTS`; I/O → `CONNECTION`; timeouts (10 s discovery, 30 s call) → `TIMEOUT`.
- **Results.** JSON object → text (JSON) + structured map; other JSON → its text; non-JSON → text.
- **Security is the reference trust boundary** (`_security.py`): HTTPS or literal loopback only
  (no DNS; inet_aton spellings, IPv4-mapped and wildcard forms count), for discovery, tool calls
  and token URLs; every redirect hop (max 5) is re-validated, never followed from remote into
  loopback, and auth headers, query and body are dropped across origins; a manual fetched from a
  non-loopback origin may not declare loopback tool or token URLs; a loopback request must be
  named literally by the template, not chosen by an argument. No insecure-host escape hatch
  exists; tests use loopback servers and an in-memory `HttpClient` for "remote" hosts. An
  injected `HttpClient` must not follow redirects itself.
- **Credentials.** `CredentialProvider` feeds only the discovery request of an http manual:
  `BearerToken` → `Authorization: Bearer`, `NamedSecret` → header of that name. They override
  static headers and are overridden by the template's UTCP `auth`. Tool calls authenticate only
  through their own `auth` objects and variables, as UTCP intends.
- **Versions.** `sdkName` `none`; `sdkVersion` is this module's version (generated from the
  build), never a spec revision. `wireRevisions` `["1.0","1.1"]`: the manual format is unchanged
  between them and 1.0 manuals are accepted, with 1.1 client rules applied. The negotiated
  revision is the manual's declared `utcp_version`, unvalidated like the reference (its own
  default is the library version, e.g. `1.2.0`).
- **Provider wire shape.** `GET /utcp` → `{"utcp_version":"1.1.0","manual_version",tools}`; each
  tool's template is `{"call_template_type":"http","http_method":"POST","url":"<base>/utcp/tools/<tool>",
  "content_type":"application/json","body_field":"body"}` and its `inputs` wrap the original
  schema as the required `body` property (root `$defs`/`definitions` hoisted so refs resolve).
  Reason: the whole argument object travels as the JSON body and keeps its types; reference
  semantics would otherwise put arguments in the query string as strings. Names are made URL-safe
  (`[^A-Za-z0-9_-]` → `_`, collisions rejected), so registry names like `a.b` publish as `a_b`.
  `POST /utcp/tools/<tool>` → 200 with structured content as JSON, text-only results as
  `text/plain`, other parts as a JSON array of `{"type": "text"|"data"|"json"|"link"|"resource"|
  "unmapped", ...}` objects; tool failure 500, unknown tool 404, bad body or
  arguments 400, `UNSUPPORTED` 501, `CONNECTION` 502, `TIMEOUT` 504, always `{"error": "..."}`,
  which reference clients surface as `"<reason>: <error>"`. Base URL: configured
  (`String`/`Supplier`) or derived from the request; one containing `$` is refused because
  clients would substitute it. `apiKeyAuth(header, VAR)` / `toolAuth(map)` put an `auth` object
  on every tool template so clients know how to pass an `InboundAuthenticator`; the only `$` the
  provider ever emits is that intended `${VAR}`.

## Deviations

Spec docs vs reference (we follow the reference):

- Docs' examples carry `utcp_version: "1.0.1"` and an `info` block; the reference ignores `info`
  and never checks `utcp_version`. So do we.
- Docs order argument mapping path → body → header → query; the reference applies
  `header_fields`, then `body_field`, then path parameters, then query. Only overlapping names
  are affected.

Reference behavior kept although surprising: a `text` template's `content` is
variable-substituted, so an inline manual containing `"$schema"` fails registration (verified
against python `utcp` 1.2.0 + `utcp-text` 1.1.0); use `file` or `http` for such manuals.

Deliberate deviations from the reference:

- OAuth2 honours `expires_in` (refresh 5 s early; without it the token is cached forever, as the
  reference). When both the body and the Basic-auth attempt fail we raise `AUTHENTICATION`; the
  reference sends `Bearer None`. An unset `scope` is omitted; the reference posts `scope=None`.
- Tools of unknown or unimplemented template types are skipped and declared; the reference
  rejects the whole manual when it has no serializer for a type.
- Argument values: non-strings in headers and path become JSON text (`true`, `{"a":1}`) instead of
  Python `str()` (`True`, `{'a': 1}`); in the query, `null` is omitted, lists repeat the key (as
  aiohttp does) and other values become JSON text where aiohttp raises a `TypeError`. A non-JSON
  body is the raw string, a form-encoded object (as aiohttp `data=`), else JSON text.
- The reason phrase comes from RFC 9110 because `java.net.http` hides the server's; the reference
  prints the server's (Jetty sends `Server Error` for 500).
- A repeated `{name}` path placeholder is filled everywhere; the reference fails on the second
  occurrence. The Content-Type presence check is case-insensitive. Basic credentials are UTF-8
  (aiohttp: latin-1). Dotenv parsing covers `KEY=VALUE`, `export`, comments and quotes, without
  `${}` interpolation or multi-line values.
- `search` returns `ToolSpec`s with unprefixed names; listed input schemas drop null-valued
  keywords (the reference serializes unset pydantic fields as `null`).

## Coverage

Feature ids live in `UtcpFeatures`; tests assert them. Supported: manuals over http/text/file,
allowed protocols, http and text tool calls, api_key (header/query/cookie), basic and oauth2
client-credentials auth, variables with dotenv, tag-and-description search, the HTTP security
rules. Unsupported: YAML manuals, OpenAPI conversion (and `auth_tools`, which only applies to it),
file/sse/streamable_http/cli/websocket/mcp/graphql/tcp/udp tool templates, streaming calls, post
processors, embedding search.

## Verification

- `./gradlew :protoweft-utcp:build`: the client against hand-written manuals and a recording
  JDK `HttpServer` (argument placement, every auth type, OAuth2 caching/expiry/fallback,
  variables, protocol filtering, error format, response mapping, security rules); the provider
  hosted by `ProtoweftServer` over `LocalTools` and a `ToolRegistry`, consumed by the client,
  including inbound API-key auth announced in the manual; a manual serialized by the Python
  reference parses.
- `PythonInteropTest` (runs when `UTCP_PYTHON` points at a Python with `utcp` 1.2.0 and
  `utcp-http` 1.1.15): the reference client registered our manual from loopback with api_key
  auth and called tools; results matched (`"5"`, structured object, caller identity, error
  `"Server Error: boom"`).

## Sources

- UTCP spec 1.1 docs, utcp-specification @ `4a9ed93`:
  [protocols/http.md](https://github.com/universal-tool-calling-protocol/utcp-specification/blob/4a9ed936a863a1f5d13ad8ef1b2b8a9783d459d6/versioned_docs/version-1.1/protocols/http.md),
  [migration-v1.0-to-v1.1.md](https://github.com/universal-tool-calling-protocol/utcp-specification/blob/4a9ed936a863a1f5d13ad8ef1b2b8a9783d459d6/versioned_docs/version-1.1/migration-v1.0-to-v1.1.md),
  [for-tool-providers.md](https://github.com/universal-tool-calling-protocol/utcp-specification/blob/4a9ed936a863a1f5d13ad8ef1b2b8a9783d459d6/versioned_docs/version-1.1/for-tool-providers.md).
- Python reference, python-utcp @ `bb8e2f6` (core 1.2.0, http 1.1.15):
  [utcp_client_implementation.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/core/src/utcp/implementations/utcp_client_implementation.py),
  [default_variable_substitutor.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/core/src/utcp/implementations/default_variable_substitutor.py),
  [tag_search.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/core/src/utcp/implementations/tag_search.py),
  [utcp_manual.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/core/src/utcp/data/utcp_manual.py),
  [tool.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/core/src/utcp/data/tool.py),
  [http_call_template.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/http/src/utcp_http/http_call_template.py),
  [http_communication_protocol.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/http/src/utcp_http/http_communication_protocol.py),
  [_security.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/http/src/utcp_http/_security.py),
  [_errors.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/http/src/utcp_http/_errors.py),
  [text_communication_protocol.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/text/src/utcp_text/text_communication_protocol.py),
  [file_communication_protocol.py](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/file/src/utcp_file/file_communication_protocol.py).
