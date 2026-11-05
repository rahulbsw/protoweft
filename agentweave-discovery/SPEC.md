---
id: agentweave-discovery
type: module-design
status: draft
title: agentweave-discovery — capability catalog and on-demand tool discovery
parent: agentweave-architecture
---

# agentweave-discovery

## Responsibility

Let a model work with a large set of capabilities without carrying every full tool schema on
every turn. The catalog ingests any `ToolSource` (MCP servers, UTCP manuals, A2A agents as
`<agent>.ask`, local tools) and answers searches with a few relevant capabilities. Full schemas
are sent only for capabilities that are selected. The module is independent of any model
provider: it depends on `agentweave-core` only and makes no model calls.

## Boundary

Public API:
- **Catalog and search:** `CapabilityCatalog` (builder, `search`, `invoke`, `refresh`,
  `markUnavailable`), `CapabilityCard`, `CapabilityHint`, `SourcePolicy`, `SchemaCache`,
  `DiscoveryQuery`, `DiscoveryResult`, `Confidence` (+ `Policy`), `FallbackPolicy`.
- **Conversation state and the generic fallback:** `DiscoverySession`, and `DiscoveryTools` (a
  `ToolSource`).
- **Extension points:** the SPIs `Embedder` and `CapabilityIndex`, with `HybridIndex` as the
  default index.
- **Raw-JSON provider payloads:** `AnthropicPayloads`, `OpenAiPayloads`, `ProviderToolNames`.

Embedders live in `agentweave-discovery-embeddings`. Typed official-SDK adapters and framework
hooks build on this module and never re-implement search.

## Decisions

- **Cards are separate from schemas.** A card is what a model reads before selecting a tool:
  - qualified name, protocol, negotiated wire revision and SDK version (taken from the
    connector's `ComplianceReport`)
  - title, summary (the first sentence, at most 160 chars), tags, parameter names
  - required scopes and behavior hints
  - a schema hash and a token estimate (canonical JSON chars / 4, an estimate only)

  Full `ToolSpec`s live in `SchemaCache`, keyed by `<source>.<tool>`.
- **Incremental refresh.** The schema hash is SHA-256 of the canonical description, input
  schema and metadata, so `refresh` re-indexes (and re-embeds) only capabilities whose hash or
  card changed. A source whose listing fails keeps its previous cards and is marked unavailable
  with the reason.
- **Exact filters come before ranking.**
  - The filters: protocol, exact wire revision, source, required and excluded hints, the
    caller's scopes, and availability.
  - They are a candidate predicate, so they never trade off against relevance.
  - Scopes come from the `CallerIdentity` attribute `scopes`. A capability with no required
    scopes is visible to everyone. Per-tool scope overrides replace the source default.
- **No silent omission, no leaking.**
  - Every result reports matches hidden because their source is unavailable, per source and by
    name in the text the model sees.
  - Matches hidden by permission are only a count on `DiscoveryResult`. They're kept out of
    model-facing text, so their existence doesn't leak.
  - A forbidden capability fails exactly like an unknown one in `invoke`, `describe` and
    sessions.
- **Ranking.**
  - **Keyword:** a field-weighted BM25 bag (k1 = 1.2, b = 0.75). Field weights: name 3, title 2,
    summary 2, tags 2, parameters 1, source 1, description 1. The tokenizer splits camelCase,
    snake_case, kebab-case and dots, lowercases, drops a short stopword list, and trims simple
    plurals.
  - **Vectors:** cosine over normalized vectors, with `embedQuery` used for queries. Vector-only
    matches below a floor are dropped.
  - **Fusion:** Reciprocal Rank Fusion with k = 60 (Cormack, Clarke and Büttcher, SIGIR 2009).
    Keyword-only mode ranks by BM25 alone.
  - BM25 is our own implementation: the target of about 5,000 capabilities fits in memory, and
    Lucene 10 would raise the Java baseline.
- **Confidence uses relative signals only.** Raw BM25 isn't comparable across queries. Signals
  are:
  - query-term coverage of the top hit
  - the top-1/top-2 margin
  - keyword/vector agreement
  - the top cosine

  The default thresholds (`Confidence.Policy.DEFAULT`) are **provisional** until the evaluation
  harness tunes them.
- **Fallback stages.**
  - **`PROGRESSIVE`** (default): on LOW confidence, re-search with 3× the limit and no vector
    floor (stage `BROADENED`). If names-only is enabled, it then adds up to 50 cards of other
    candidates without schemas, from the hit sources first (stage `NAMES_ONLY`).
  - **`ALL_SCHEMAS`:** reproduces the all-tools baseline on low confidence.
  - **`NONE`:** only reports.

  The stage is always in the result.
- **Sessions.** `DiscoverySession` keeps selected capabilities' schemas loaded across turns, like
  Anthropic expanding references from history. It evicts least-recently selected entries beyond
  32, and drops capabilities that later disappear.
- **Generic meta-tools.** `DiscoveryTools` has three tools: `search_capabilities` (cards,
  confidence, stage, hidden counts), `describe_capabilities` (schemas, marked loaded in the
  session) and `call_capability`. Schemas are fetched by an explicit describe step, because the
  requirement is "full schemas only when selected". `inlineSchemas` on a query trades a round
  trip for tokens; which default is better is for the evaluation to show.
- **Provider payloads** are plain maps, built from the catalog with the same filters, so only
  permitted and available capabilities are ever sent.
  - **Anthropic** ([tool search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)):
    - Tools are sent with `defer_loading: true`, plus our non-deferred `search_capabilities`
      tool, which answers with a `tool_result` of `tool_reference` blocks.
    - The hosted alternatives are `tool_search_tool_regex_20251119` and
      `tool_search_tool_bm25_20251119`.
    - Constraints: at most 10,000 deferred tools, no `cache_control` on deferred tools, at least
      one non-deferred tool (the search tool).
  - **OpenAI** ([tool search](https://developers.openai.com/api/docs/guides/tools-tool-search),
    Responses API):
    - Client-executed `tool_search`, answered with `tool_search_output` holding full function
      definitions.
    - Optional `defer_loading` functions, grouped by per-source `namespace`, with deferral on the
      inner functions.
    - Hosted alternative: `{"type":"tool_search"}`.
- **Provider tool names.** Both providers accept `^[a-zA-Z0-9_-]{1,64}$`. `ProviderToolNames`
  maps `.` to `__`, replaces other characters with `_`, and hashes over-long names. The mapping
  is reversible and rejects collisions.

## Coverage and limitations

- One in-memory index per catalog, behind the `CapabilityIndex` SPI. No external vector store.
- Card summaries are English-oriented: the tokenizer, stopwords and plural trimming target
  English.
- Re-checking availability calls the source's probe on every search. Keep probes cheap.
- **Retrieval quality is unmeasured** until the evaluation harness (`evals/tool-discovery`) runs.
  No claim of accuracy or of unchanged accuracy is made here.

## Verification

- **Unit and end-to-end tests (33):**
  - BM25 against a hand-computed score, field weights, the tokenizer, RRF arithmetic, the
    vector floor
  - every exact filter; permission hiding without leaks; availability (marked, probe, failed
    refresh)
  - each fallback mode; inlined schemas; incremental re-embedding with a counting embedder
  - session LRU; search, describe and call end to end
  - exact Anthropic and OpenAI payload shapes; name mapping
  - a 5,000-capability smoke test (median 6.85 ms per keyword search on an Apple M4 Pro)
- **With the real offline embedder:** `agentweave-discovery-embeddings`'s `HybridCatalogTest`
  shows hybrid search finding a paraphrase that keyword search misses.
