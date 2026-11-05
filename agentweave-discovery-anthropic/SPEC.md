---
id: agentweave-discovery-anthropic
type: module-design
status: draft
title: agentweave-discovery-anthropic — typed Anthropic tool-search adapter
parent: agentweave-discovery
---

# agentweave-discovery-anthropic

## Responsibility

Turn the provider-neutral `CapabilityCatalog` into Anthropic Messages API request parts built
from the official Anthropic Java SDK types, and answer the model's tool calls. It never sends a
request: users put the parts into their own `MessageCreateParams` and send them with their own
`AnthropicClient`.

## Boundary

Public API (package `io.agentweave.discovery.anthropic`):
- **`AnthropicDiscovery`:** built from a catalog, with an optional `DiscoverySession`,
  `CallerIdentity`, shared `ProviderToolNames`, and a `Mode`: `CATALOG_SEARCH` (default),
  `HOSTED_REGEX` or `HOSTED_BM25`.
  - `tools(DiscoveryQuery)` / `tools()` return `List<ToolUnion>`: the search tool, then every
    permitted, available capability as a deferred `Tool`.
  - `handle(ToolUseBlock)` returns a `ToolResultBlockParam`.
  - `process(Message)` returns an `AnthropicTurn`.
  - Also: `tool(ToolSpec, boolean)`, `searchTool()`, `hostedSearchTool(Mode)` and
    `toolReferenceResult(...)`.
- **`AnthropicTurn`:** the assistant `MessageParam` to append, the tool results,
  `toolResultMessage()`, and `appendTo(MessageCreateParams.Builder)`.

Depends on `agentweave-discovery` and `com.anthropic:anthropic-java`. Search, filtering and
naming aren't re-implemented. Filtering and names come from `AnthropicPayloads`, and searching
from `CapabilityCatalog.search`.

## Decisions

- **Same wire JSON as the raw payloads.** The typed tools are converted from
  `AnthropicPayloads` maps field by field:
  - `name`, `description` and `defer_loading` map to their typed fields.
  - In `input_schema`, `type`, `properties` and `required` use the typed fields. Every other
    keyword goes through as an additional property via `JsonValue.from`.
  - A schema without `type` stays without it, as in the raw map.
  - Hosted tools are `ToolSearchToolRegex20251119` / `ToolSearchToolBm25_20251119`, with the
    `*_20251119` type and the SDK's default name.
- **Deferral.** Capabilities carry `defer_loading: true` and no `cache_control`. The search tool
  (ours or hosted) is never deferred, so at least one tool is always non-deferred. More than
  10,000 deferred tools is rejected (`AnthropicPayloads.MAX_DEFERRED_TOOLS`).
  `keepSelectedLoaded(true)` sends session-selected capabilities non-deferred. It's off by
  default, because Anthropic already keeps referenced tools loaded from history.
- **One caller for sending and calling.** `tools(filter)` replaces the filter's caller with the
  instance's caller, the same one `handle` uses. With a session, the session's caller applies,
  and a different explicit caller is rejected.
- **`handle`:**
  - `search_capabilities` (catalog mode only) runs `catalog.search` for the caller and records
    the hits in the session. It answers with `tool_result` content of `tool_reference` blocks.
    The JSON is identical to `AnthropicPayloads.searchResult`.
  - Bad search input is an `is_error` result.
  - A capability call maps the provider name back and calls `catalog.invoke`. The outcome text
    is the content, and error outcomes set `is_error`. Non-text parts are described in brackets,
    never dropped.
  - Unknown names and capabilities the caller may not see give the identical
    `unknown tool: <name>` error, so a forbidden capability doesn't leak. Other
    `AgentWeaveException`s become error results. Other exceptions propagate.
- **`process(Message)`.**
  - Answers every `tool_use` block, in order.
  - Returns `Message.toParam()` as the assistant message, so `server_tool_use` and
    `tool_search_tool_result` blocks are echoed back unchanged. No `tool_result` is produced for
    `srvtoolu_` ids.
  - Records capabilities found by a hosted search in the session.
- **Names.** Use one instance per conversation, or share one `ProviderToolNames` (builder
  `names(...)`), so that provider names resolve back.

SDK: `com.anthropic:anthropic-java:3.5.0`, the latest on Maven Central when written. It uses
Jackson 2 (`com.fasterxml`), which doesn't clash with the Jackson 3 used elsewhere. Docs:
[tool search tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool).

## Coverage and limitations

- Covers the Messages API non-streaming `Message` only. Streaming events and the beta
  namespace (`client.beta()`) aren't adapted.
- Results are text only: images and other binary outcome parts are described, not sent as image
  blocks.
- A search with no hits answers with an empty `tool_reference` list, as the raw payloads do.
  The API's acceptance of an empty custom `tool_result` content list is unverified.
- Like the parent module, no claim is made about retrieval accuracy or about the model's tool
  selection.

## Verification

- `./gradlew :agentweave-discovery-anthropic:build` runs 20 offline tests:
  - typed `tools` JSON equals the raw `AnthropicPayloads` JSON, for the catalog and both hosted
    variants, including a rich schema and a schema without `type`
  - deferral flags; the search tool isn't deferred; no `cache_control`
  - permission and availability filtering, and the caller override
  - `keepSelectedLoaded`
  - `handle` for search (JSON equal to raw), a capability, an error outcome, invalid search
    input, unknown, permission-forbidden (identical to unknown, never invoked) and hosted mode
  - non-text rendering
  - hand-written `Message` fixtures, following the docs, with `server_tool_use`,
    `tool_search_tool_result` and `tool_use`; the echoed blocks are checked to be unchanged
- `LiveRoundTripTest` runs only when `ANTHROPIC_API_KEY` and `ANTHROPIC_MODEL` are set. It
  checks that the model calls `search_capabilities` and then the deferred `weather__forecast`.
