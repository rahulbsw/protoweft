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
  - **Fusion (default: convex, α = 0.8).** `alpha · cos' + (1 − alpha) · bm25'` over per-query
    min-max-normalized scores, 0 for a list a capability isn't in (Bruch et al., "An Analysis of
    Fusion Functions for Hybrid Retrieval", TOIS 2023). `HybridIndex.Fusion` also offers weighted
    Reciprocal Rank Fusion (Cormack, Clarke and Büttcher, SIGIR 2009); the earlier default was
    unweighted RRF, k = 60. Keyword-only mode ranks by BM25 alone. Embedding the full description
    is an option, off by default.
    - *Why convex 0.8:* chosen from 16 variants on the dev split (MCPToolBench++ and five ToolRet
      subsets, N = 1,000 and 10,000; commit `fb7b4ae` plus the options), then judged once on the
      held-out split against RRF: nDCG@10 +1.5 pts [+0.4, +2.7] at N = 1,000 and +2.1 [+1.2, +3.0]
      at 10,000; R@5 +2.4 [+1.1, +4.0] at 10,000 but only +0.4 [−0.8, +1.5] at 1,000 (not
      significant). No interval showed a loss; the user chose to adopt it on that evidence.
      Dense-only ranked below every hybrid variant at 10,000.
  - BM25 is our own implementation: the target of about 5,000 capabilities fits in memory, and
    Lucene 10 would raise the Java baseline.
- **Confidence uses relative signals only.** Raw BM25 isn't comparable across queries. Signals
  are:
  - query-term coverage of the top hit
  - the top-1/top-2 margin
  - keyword/vector agreement
  - the top cosine

  Thresholds are chosen as follows:
  - **An explicit policy is never overridden.**
  - **Hybrid presets:** when none is set, the catalog uses the preset calibrated for the embedder
    (`BGE_SMALL_EN_V15`, `ALL_MINILM_L6_V2`, matched by `Embedder.name()`).
  - **Everything else:** keyword-only search and unknown embedders use `DEFAULT`.

  Why presets:
  - **Calibration result.** The `calibrate` command (dev/test split over ToolRet and
    MCPToolBench++) showed that the earlier provisional thresholds flagged only 1.4% of bge-hybrid
    misses as LOW. Re-run for the convex default, the bge preset (0.6, 0.6, 0.90, 0.65, 0.05) flags
    46.3% of misses with 20.2% false alarms on the held-out split, and is right 83.2% of the time
    when HIGH (the previous preset on convex fusion: 48.2%, 21.4%, 78.1%).
  - **Why the keyword default stays:** tuning it would cut miss detection from 58% to 18%, which
    is the wrong trade for a fallback whose job is to avoid silent omission.
  - **Limitation:** the fallback still doesn't flag more than half of hybrid misses. It reduces
    silent omission; it doesn't eliminate it.
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
- **Models get `query` and `limit` only.** Every search tool AgentWeave hands a model — the
  generic `search_capabilities` and the native Anthropic/OpenAI search tools — uses
  `Payloads.searchSchema()`, and `DiscoveryTools.query` ignores any other key. Protocol, source,
  hint and permission filters are the application's (`DiscoveryQuery`, `SourcePolicy`, caller
  scopes). *Why (bug post-mortem):* the meta-tool used to offer `protocols`, `sources` and
  `read_only`. In the model-in-the-loop smoke run (commit `48b74f8`), GPT-5.4 set them on 7 of 10
  tasks — `sources: ["all"]`, `protocols` without `local`, `read_only` on unannotated tools —
  every such search came back empty, and the model answered that no tool existed. A model can't
  know source names, protocol labels or which tools carry hints; the application can. An empty
  result now says nothing matched and suggests different words.
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
- **What is measured.** Results, bound to commits, are in `docs/discovery/evaluation.md` and
  owned by `evals-tool-discovery`.
  - Retrieval: hybrid R@5 is 0.863 on MCPToolBench++'s 87 tools, falling to 0.521 with about 44k
    distractors.
  - Model in the loop (commit `021a2f3`; Claude Sonnet 5.5 and GPT-5.4 through a LiteLLM proxy;
    50 tasks, 1 trial, N = 50/200/1,000): top-k and generic search cut input tokens per task by
    up to ~180k (N = 1,000), and no selection difference against all-tools was detected — but
    intervals are wide (up to −20 points for Sonnet at N = 1,000 can't be excluded), so no claim
    of unchanged accuracy is made. Losses at large N come mostly from retrieval misses.
  - Not measured: the provider-native adapters (the proxy dropped deferred loading).

## Verification

- **Unit and end-to-end tests (33):**
  - BM25 against a hand-computed score, field weights, the tokenizer, weighted RRF and convex arithmetic, the
    vector floor
  - every exact filter; permission hiding without leaks; availability (marked, probe, failed
    refresh)
  - each fallback mode; inlined schemas; incremental re-embedding with a counting embedder
  - session LRU; search, describe and call end to end
  - exact Anthropic and OpenAI payload shapes; name mapping
  - a 5,000-capability smoke test (median 6.85 ms per keyword search on an Apple M4 Pro)
- **With the real offline embedder:** `agentweave-discovery-embeddings`'s `HybridCatalogTest`
  shows hybrid search finding a paraphrase that keyword search misses.
