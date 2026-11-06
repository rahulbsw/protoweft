---
id: protoweft-discovery-openai
type: module-design
status: draft
title: protoweft-discovery-openai — typed OpenAI Responses tool-search adapter
parent: protoweft-discovery
---

# protoweft-discovery-openai

## Responsibility

Turn the provider-neutral `CapabilityCatalog` into OpenAI Responses API tool-search request parts,
as official OpenAI Java SDK types, and answer the model's `tool_search_call` and `function_call`
output items from the catalog. It makes no model calls: users put the params on their own
`ResponseCreateParams` and send them with their own `OpenAIClient`.

## Boundary

- Depends on `protoweft-discovery` (`api`) and `com.openai:openai-java:4.100.0` (`api`: SDK
  types are in the public API). Nothing else.
- Never re-implements search, filtering or naming. Which capabilities are sent, their order, the
  namespace grouping, the search tool's description and schema, and the provider names all come
  from `OpenAiPayloads` and `ProviderToolNames` (shared instance).
- Public API (package `io.github.rahulbsw.protoweft.discovery.openai`), one class `OpenAiDiscovery`:
  - `of(catalog)`, `builder(catalog)`, `builder(session)`; builder options `caller`,
    `mode(CLIENT_SEARCH | HOSTED)`, `namespaces`, `sessionTools`, `answerUnknownCalls`, `strict`,
    `names`.
  - Request side: `tools()` / `tools(DiscoveryQuery)` → `List<Tool>`, `clientSearchTool()`,
    `hostedSearchTool()`, `deferredTools(filter)`, `function(spec, deferred)`.
  - Response side: `handle(ResponseOutputItem)` → `Optional<ResponseInputItem>`,
    `nextInput(Response)` → `List<ResponseInputItem>`, `toolSearchOutput(callId, names)`,
    `searchOutput(callId, arguments)`, `functionCallOutput(ResponseFunctionToolCall)`,
    `catalogName(providerName)`.

```java
OpenAiDiscovery discovery = OpenAiDiscovery.builder(session).build();
Response r = client.responses().create(ResponseCreateParams.builder()
        .model("gpt-5.4").input("...").tools(discovery.tools()).build());
List<ResponseInputItem> next = discovery.nextInput(r);   // send with previousResponseId(r.id())
```

## Decisions

- **Same wire JSON as the raw payloads.** Typed params serialize (with the SDK's
  `ObjectMappers.jsonMapper()`) to exactly what `OpenAiPayloads` produces for the same catalog and
  filter. Tests compare the two as JSON trees.
- **Modes.**
  - `CLIENT_SEARCH` (default): `{"type":"tool_search","execution":"client",...}`. A client
    `tool_search_call` is answered by a catalog search for the caller with a `tool_search_output`
    (`execution: client`, echoed `call_id`, `status: completed`) holding full, non-deferred
    function definitions.
  - `HOSTED`: `{"type":"tool_search"}` plus every permitted, available capability as a
    `defer_loading` function. With `namespaces`, one `namespace` per source; deferral sits on the
    inner functions, never on the namespace.
  - Hosted `tool_search_call` / `tool_search_output` items need no answer and are ignored.
- **Caller and session.** The caller defaults to the session's caller, else anonymous; a different
  explicit caller is rejected. Search hits and called capabilities are recorded in the session.
  `sessionTools` (default off) re-sends the session's selected capabilities as normal functions, for
  requests that don't carry the history that loaded them. It's off by default because that history
  already makes them callable.
- **Function calls.** The provider name is mapped back (if this instance didn't build the request,
  the catalog's current names are registered first). Then the visibility check runs, then argument
  parsing, then `catalog.invoke`. The answer is a `function_call_output` whose string output holds
  the outcome text; failures start with `Error:`.
- **No leaking.** Unknown and forbidden names both get `Error: unknown tool: <name as called>`.
  Visibility is checked before arguments are parsed, so malformed arguments don't reveal that a
  forbidden capability exists. `answerUnknownCalls(false)` leaves calls to non-catalog names to the
  application.
- **Invalid search arguments** (no `query`) are answered with a `tool_search_output` that loads no
  tools.
- **`strict` is omitted by default**, as in `OpenAiPayloads`. The SDK's `FunctionTool` builder
  requires the field, so it's set to `JsonMissing`, which keeps it off the wire. `strict(Boolean)`
  sends it explicitly. That changes the wire JSON, so it's opt-in. Omitting it is safe:
  according to [function calling](https://developers.openai.com/api/docs/guides/function-calling#strict-mode),
  a Responses request without `strict` normalizes the schema to strict mode when possible and
  falls back to non-strict, best-effort calling otherwise. It is never rejected.

## SDK and documentation

- SDK: `com.openai:openai-java:4.100.0` (Maven Central). Types used: `Tool`, `ToolSearchTool`
  (`Execution.CLIENT`), `FunctionTool` (`deferLoading`), `NamespaceTool` (`Tool.Function`),
  `ResponseToolSearchOutputItemParam`, `ResponseInputItem` (`ofToolSearchOutput`,
  `ofFunctionCallOutput`), `Response`, `ResponseOutputItem`, `ResponseToolSearchCall`,
  `ResponseFunctionToolCall`, `com.openai.core.JsonValue`, `com.openai.core.ObjectMappers`.
- Docs: [tool search](https://developers.openai.com/api/docs/guides/tools-tool-search)
  (Responses API only, `gpt-5.4` and later),
  [function calling](https://developers.openai.com/api/docs/guides/function-calling).

## Coverage and limitations

- Function tools and namespaces only. MCP-server tools, `additional_tools` input items and custom
  tools aren't produced.
- The client-mode `tool_search_output` carries plain functions (not namespaces).
- Output is a text string. Image/file outputs (`Content.Data`) become a visible marker and aren't
  forwarded.
- Catalog input schemas are sent unchanged. They aren't rewritten for strict mode.
- Retrieval quality is that of the catalog and is unmeasured here.

## Verification

`./gradlew :protoweft-discovery-openai:build`. All offline, no key; 20 tests, plus 1 live test:
- **`WireJsonTest` (8):**
  - typed vs raw JSON for the client search tool, the hosted search tool, flat deferred functions,
    namespaces, `tool_search_output` and `searchOutput`
  - permission and availability filtering
  - `strict` omitted or set
  - params placed in a `ResponseCreateParams` body
- **`HandleTest` (12):**
  - a client `tool_search_call` answered and recorded in the session; invalid search arguments
  - hosted steps and messages ignored
  - a function call; name resolution across instances
  - unknown names; forbidden names (identical to unknown, and not executed); thrown, invalid-argument
    and unavailable failures
  - `answerUnknownCalls(false)`; `sessionTools`
  - hand-written client and hosted `Response` JSON fixtures (written from the docs) parsed and
    answered by `nextInput`
- **`LiveOpenAiTest` (1):** one real client-search round trip. It runs only when `OPENAI_API_KEY`
  and `OPENAI_MODEL` are set, and is skipped otherwise.
