# Tool discovery

When an application connects to many MCP servers, UTCP manuals and A2A agents, sending every
tool schema to the model on every turn costs context tokens, and tool-selection accuracy tends to
drop as the list grows. Tool discovery lets the model **search** a catalog instead: each turn
carries a few short capability cards, and full schemas are sent only for the capabilities the
model selects.

!!! warning "Measure before you rely on it"
    Discovery changes what the model sees, and that can change which tool it picks. Retrieval
    quality is measured by the evaluation harness in `evals/tool-discovery`; see
    [Evaluation](evaluation.md) for the measured numbers and what has not been measured. Don't
    assume accuracy is unchanged for your catalog: run the harness on your own tasks.

## Modules

| Module | Use it for |
|---|---|
| `protoweft-discovery` | The catalog, hybrid search, filters, fallback, sessions, the generic meta-tools, and raw-JSON provider payloads. Depends only on the core. |
| `protoweft-discovery-embeddings` | Semantic ranking with an offline in-process model (bge-small-en-v1.5, quantized) on ONNX Runtime alone; the model comes in `protoweft-model-bge-small-en-v15`. For any LangChain4j `EmbeddingModel`, use `LangChain4jEmbedder` from `protoweft-langchain4j`. |
| `protoweft-discovery-anthropic` | Typed Anthropic Messages API request parts (native tool search) on `com.anthropic:anthropic-java`. |
| `protoweft-discovery-openai` | Typed OpenAI Responses API request parts (native tool search) on `com.openai:openai-java`. |

Protoweft never calls a model. The adapters build request parts and process responses; your
application, or LangChain4j, sends the requests.

## Build a catalog

```java
ToolRegistry registry = new ToolRegistry()
        .register("files", mcpFilesConnector)
        .register("weather", utcpWeatherConnector)
        .register("travel", a2aTravelConnector);    // appears as one capability, travel.ask

CapabilityCatalog catalog = CapabilityCatalog.builder()
        .registry(registry)
        .embedder(OfflineEmbedders.bgeSmallEnV15())  // optional; without it search is keyword-only
        .build();                                    // lists every source once
```

Each capability gets a **card** holding its qualified name (`<source>.<tool>`), the protocol,
the negotiated wire revision and the SDK version (from the connector's `ComplianceReport`), a
one-sentence summary, tags, parameter names, required scopes, behavior hints (read-only,
destructive, …), and a schema hash. The full `ToolSpec` is kept separately in the catalog's
`SchemaCache`.

Call `catalog.refresh()`, or `refresh("files")`, when a source's tools change. Only capabilities
whose schema hash changed are re-indexed and re-embedded. A source whose listing fails keeps its
previous cards and is marked unavailable.

### Source policies

```java
CapabilityCatalog catalog = CapabilityCatalog.builder()
        .source("files", filesConnector, SourcePolicy.builder()
                .requiredScopes("files:read")              // every tool of this source
                .toolScopes("delete_file", "files:admin")  // override for one tool
                .availability(() -> filesConnectorHealthy) // checked on every search
                .build())
        .source("local", localTools)
        .build();
```

## Search

```java
DiscoveryResult result = catalog.search(DiscoveryQuery.builder("book a flight to Berlin")
        .limit(5)
        .protocols("a2a", "mcp")              // exact filters, applied before ranking
        .require(CapabilityHint.READ_ONLY)
        .caller(callerIdentity)               // scopes from the "scopes" attribute
        .build());

for (DiscoveryResult.Hit hit : result.hits()) {
    System.out.println(hit.card().name() + " — " + hit.card().summary());
}
System.out.println(result.confidence().level() + " / " + result.stage());
```

How a search works:

1. **Exact filters:** protocol, exact wire revision, source, required and excluded hints, the
   caller's scopes, and availability. Everything filtered out is never ranked.
2. **Ranking:** a field-weighted BM25 (name weighted above summary and tags, which are weighted
   above parameters and description). With an embedder, cosine similarity is added and the two
   scores are blended: 0.8 × normalized similarity + 0.2 × normalized BM25 (the default
   `HybridIndex.Fusion`, chosen by measurement; weighted Reciprocal Rank Fusion is an option).
3. **Confidence:** `HIGH`, `MEDIUM` or `LOW`, from relative signals (query-term coverage, the
   margin between the top two scores, keyword/vector agreement, top similarity). The default
   thresholds are provisional; the evaluation harness has a `calibrate` command that suggests
   values for your data.
4. **Fallback** on `LOW` confidence, so relevant capabilities aren't silently omitted:

   | `FallbackPolicy.Mode` | What happens | `result.stage()` |
   |---|---|---|
   | `PROGRESSIVE` (default) | Re-search with 3× the limit and no similarity floor; then add up to 50 more candidates as cards only, no schemas | `BROADENED`, then `NAMES_ONLY` |
   | `ALL_SCHEMAS` | Return every filter-passing capability with its schema (the all-tools baseline) | `ALL_SCHEMAS` |
   | `NONE` | Report low confidence only | `NONE` |

Every result also reports `hiddenUnavailable()` (matches hidden because their source is
unavailable, per source) and `hiddenByPermission()` (a count). Permission-hidden matches are
never named in text shown to a model, and calling a capability you may not see fails exactly
like calling an unknown one.

Set `inlineSchemas(n)` on the query to include full schemas for the top `n` hits. This saves a
round trip at the cost of tokens.

## Give a model discovery

### Any model: the generic meta-tools

`DiscoveryTools` is a `ToolSource` with three tools:

| Tool | Does |
|---|---|
| `search_capabilities` | Returns cards, confidence, fallback stage and hidden counts. Arguments: `query` and optional `limit`. At least 10 cards come back (`withDefaultLimit` changes that); a model may ask for up to 50 but not fewer. Protocol, source and read-only filters are set by the application on `DiscoveryQuery`, not by the model. |
| `describe_capabilities` | Returns full input schemas for `names`, and marks them loaded in the session. |
| `call_capability` | Calls `name` with `arguments`, re-checking permission and availability. |

```java
DiscoverySession session = new DiscoverySession(catalog, caller, DiscoverySession.DEFAULT_MAX_LOADED);
DiscoveryTools metaTools = DiscoveryTools.forSession(session);
```

A session remembers what has been selected, so those schemas stay loaded across turns without
another search. The least recently selected entries are evicted beyond the limit. Serve the
meta-tools like any tool source: over MCP (`McpToolServer.http(DiscoveryTools.of(catalog))`),
through the LangChain4j adapter, or call them directly.

### Anthropic: native tool search

```java
AnthropicDiscovery discovery = AnthropicDiscovery.builder(catalog).caller(caller).build();

MessageCreateParams.Builder request = MessageCreateParams.builder()
        .model(model)
        .maxTokens(1024)
        .tools(discovery.tools())                // search tool + deferred catalog tools
        .addUserMessage("What's the weather in Oslo?");

Message response = client.messages().create(request.build());
AnthropicTurn turn = discovery.process(response);  // answers searches and capability calls
if (turn.hasToolResults()) {
    turn.appendTo(request);                        // assistant message + tool results
    response = client.messages().create(request.build());
}
```

Every permitted, available capability is sent with `defer_loading: true`, so it stays out of
the model's context. Our non-deferred `search_capabilities` tool runs the catalog search,
including its filters and fallback, and answers with `tool_reference` blocks, which make
Anthropic load those schemas. Anthropic's hosted regex and BM25 search are available with
`.mode(Mode.HOSTED_REGEX)` or `.mode(Mode.HOSTED_BM25)`. In those modes Anthropic ranks, not the
catalog.

### OpenAI: native tool search (Responses API)

```java
OpenAiDiscovery discovery = OpenAiDiscovery.builder(catalog).caller(caller).build();

ResponseCreateParams.Builder request = ResponseCreateParams.builder()
        .model(model)
        .tools(discovery.tools())                // client-executed tool_search
        .input("What's the weather in Oslo?");
Response response = client.responses().create(request.build());
List<ResponseInputItem> next = discovery.nextInput(response);  // tool_search_output + call outputs
```

In the default `CLIENT_SEARCH` mode, the model's `tool_search_call` is answered by the catalog
with full function definitions for the hits. `HOSTED` mode sends deferred functions, optionally
grouped into per-source namespaces, for OpenAI's hosted search. OpenAI supports tool search only
on `gpt-5.4` and later models.

### LangChain4j

```java
CatalogToolSearchStrategy search = CatalogToolSearchStrategy.from(catalog);

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(chatModel)
        .toolProvider(search.toolProvider())
        .toolSearchStrategy(search)
        .build();
```

This uses LangChain4j's own (experimental) tool-search API. The model first sees only
`search_capabilities`. Capabilities it finds become available on later turns and stay available
through chat memory. Names-only candidates are listed in the text but not loaded.

### Spring AI

`CatalogToolIndex` (in `protoweft-spring-ai`) makes the catalog the index of Spring AI's own
`ToolSearchToolCallingAdvisor`: the model starts with `toolSearchTool` and loads definitions only
for what the catalog finds for the caller. Names-only candidates aren't loaded. See
[Spring AI: tool search](../integrations/spring-ai.md#tool-search-with-the-catalog).

### ARGI

`CatalogToolSelectionInterceptor` (in `protoweft-argi`) narrows the tools of an ARGI agent on each
model call to the catalog's hits, instead of ARGI's LLM-based tool selector. See
[ARGI: catalog tool selection](../integrations/argi.md#catalog-tool-selection).

### Spring Boot

```yaml
protoweft:
  discovery:
    enabled: true
    fallback: progressive          # progressive | all-schemas | none
    sources:
      files:
        required-scopes: [files:read]
    expose-mcp: true               # serve the meta-tools over MCP
    mcp-path: /mcp-discovery
```

The starter builds a `CapabilityCatalog` bean over the registry, and uses an `Embedder` bean if
you define one. It refreshes the catalog after the declared connectors have started; until then,
those connectors report unavailable. With Spring Security, `SCOPE_x` authorities become the
caller's scopes.

## Provider tool names

Catalog names contain a dot (`files.read_file`). Anthropic and OpenAI only accept
`^[a-zA-Z0-9_-]{1,64}$`, so the adapters map names reversibly with `ProviderToolNames`: `.`
becomes `__`, other characters become `_`, and over-long names are shortened with a hash.
Colliding names are rejected. Use the adapter's `catalogName(providerName)` to map back.

## Limitations

- The default index is in memory, sized for about 5,000 capabilities; the `CapabilityIndex` SPI
  is the seam for an external store.
- The tokenizer, stopwords and plural handling are English-oriented.
- Gemini, Bedrock Converse and Mistral have no native tool search; use the generic meta-tools.
  The Anthropic-on-Bedrock variant isn't built.
- The live round trips of the Anthropic and OpenAI adapters are written as opt-in tests and run
  only when API keys are provided.
