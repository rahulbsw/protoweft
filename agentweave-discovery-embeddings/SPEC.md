---
id: agentweave-discovery-embeddings
type: module-design
status: draft
title: agentweave-discovery-embeddings — offline and LangChain4j-backed embedders
parent: agentweave-architecture
---

# agentweave-discovery-embeddings

## Responsibility

Supplies implementations of the `io.agentweave.discovery.Embedder` SPI (owned by
`agentweave-discovery`) so discovery can rank capabilities semantically:

1. **Any LangChain4j `EmbeddingModel`** — a hosted model the application already uses (OpenAI,
   Ollama, Vertex AI, ...) or any other implementation — through `LangChain4jEmbedder`.
2. **Offline models** — two quantized 384-dimension English models that run in-process on the CPU
   through ONNX Runtime, with no API key, network call or model download: `OfflineEmbedders`.

## Boundary

- Depends on `agentweave-discovery` (`api`), `dev.langchain4j:langchain4j-core:1.23.0` (`api`:
  `EmbeddingModel` is in the public API), and the LangChain4j in-process model artifacts at
  `1.23.0-beta33` (scopes below). No dependency on any protocol module or on `agentweave-langchain4j`.
- Does not modify the `Embedder` SPI, store vectors, or normalize them (the discovery index does).
- Public API (package `io.agentweave.discovery.embeddings`):
  - `LangChain4jEmbedder implements Embedder` — `of(EmbeddingModel)`, `builder(EmbeddingModel)` with
    `name`, `queryPrefix`, `documentPrefix`, `batchSize`, `dimension`; accessors `model()`,
    `queryPrefix()`, `documentPrefix()`.
  - `OfflineEmbedders` — `bgeSmallEnV15()` (default), `allMiniLmL6V2()`; constants
    `BGE_QUERY_PREFIX`, `BGE_SMALL_EN_V15_NAME` (`"bge-small-en-v1.5-q"`), `ALL_MINILM_L6_V2_NAME`
    (`"all-minilm-l6-v2-q"`).

```java
Embedder offline = OfflineEmbedders.bgeSmallEnV15();
Embedder hosted = LangChain4jEmbedder.builder(openAiEmbeddingModel).dimension(1536).batchSize(256).build();
```

## Decisions

- **Default model: bge-small-en-v1.5 (quantized).** Built for retrieval with an explicit
  query/passage asymmetry, which matches discovery (a short query against capability documents),
  512-token window (the all-MiniLM-L6-v2 model card truncates at 256 word pieces), MIT licensed. Same
  dimension as all-MiniLM-L6-v2, ~8 MB larger. all-MiniLM-L6-v2 stays available as the smaller,
  symmetric alternative. No accuracy claim is made for either here; ranking quality belongs to the
  discovery evals.
- **Dependency scope.**
  - bge is `api`: `bgeSmallEnV15()` works with no further setup. It brings
    `langchain4j-embeddings`, ONNX Runtime and DJL tokenizers transitively; the model jar
    (24.6 MB) is about a fifth of the total in-process footprint (sizes below), so making it
    optional would save little for anyone who runs offline at all.
  - all-MiniLM-L6-v2 is `compileOnly`: users add
    `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.23.0-beta33` at runtime to use it,
    so the default classpath carries one model, not two.
  - Hosted-model users who want none of the in-process stack exclude
    `dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q`; `LangChain4jEmbedder` needs only
    `langchain4j-core`. A factory whose model is absent throws `IllegalStateException` naming the
    coordinates to add; each model class is referenced only from its own holder class, so a missing
    jar never surfaces as `NoClassDefFoundError`. Both cases are tested in an isolated class loader.
- **Prefix handling.** `embedQuery` prepends the query prefix, `embedDocument`/`embedDocuments` the
  document prefix; both default to empty. bge gets `BGE_QUERY_PREFIX`
  (`"Represent this sentence for searching relevant passages: "`, from the model card) on queries
  only; documents are embedded as given. MiniLM gets none. `documentPrefix` exists for models such
  as e5 (`"passage: "`).
- **Calls go through `embedAll`.** In LangChain4j 1.23.0 every `EmbeddingModel` method is `default`;
  `embedAll` and `doEmbed` have mutually recursive defaults. The in-process models override only
  `embedAll` (one segment runs on the caller thread, several are parallelized on the model's
  executor); newer hosted providers override `doEmbed`, which the default `embedAll` reaches.
  `embedAll` is therefore the one entry point that works for both; single texts are a one-element
  batch. `embedDocuments` splits into `batchSize` chunks (default: one call), returns `List.of()`
  for an empty list without calling the model (the in-process models reject empty lists), and
  fails with `IllegalStateException` if the model returns a different number of vectors.
- **Input and output checks.** Null text → `NullPointerException`, blank text →
  `IllegalArgumentException`, for every model alike (the in-process models reject blank text, and a
  query prefix would otherwise hide it). Vectors are copied before return. When the dimension is
  known (from the builder, or after `dimension()` has asked the model once and cached it), every
  vector is checked against it.
- **Lazy, thread-safe loading.** The offline embedders wrap the model in a package-private
  `LazyEmbeddingModel`: the factory and `dimension()` (fixed at 384) load nothing; the first
  embedding creates the model under double-checked locking, exactly once even under concurrent
  first use. A failed load is rethrown as `IllegalStateException` and retried on the next call
  (a failed model-class initializer, however, stays failed for the JVM). Each factory returns one
  shared instance per class loader. During the load the thread context class loader is pointed
  at the model class's loader, because LangChain4j reads the model file through the context
  loader, and the first embedding may run on a thread whose context loader cannot see the jar.
- **Long text.** LangChain4j does not truncate: text over 510 tokens is split into chunks whose
  embeddings are averaged by token count. Capability documents are expected to be far shorter.

## Licenses and sizes

| Artifact (Maven Central) | License | Jar size |
|---|---|---|
| `langchain4j-embeddings-bge-small-en-v15-q:1.23.0-beta33` (BAAI bge-small-en-v1.5, quantized) | MIT (weights), Apache-2.0 (LangChain4j code) | 24.6 MB |
| `langchain4j-embeddings-all-minilm-l6-v2-q:1.23.0-beta33` (sentence-transformers all-MiniLM-L6-v2, quantized) | Apache-2.0 | 16.4 MB |
| `com.microsoft.onnxruntime:onnxruntime:1.23.2` (via `langchain4j-embeddings`) | MIT | 75.3 MB |
| `ai.djl.huggingface:tokenizers:0.36.0` + `ai.djl:api:0.36.0` | Apache-2.0 | 18.7 MB + 1.0 MB |
| `langchain4j-embeddings:1.23.0-beta33` | Apache-2.0 | 0.3 MB |

Natives bundled in those jars: ONNX Runtime for linux-x64/aarch64, osx-x64/aarch64, win-x64; DJL
tokenizers for linux-x86_64/aarch64, osx-aarch64, win-x86_64 (DJL extracts its native library to
`~/.djl.ai` on first use). The tokenizer library is not bundled for osx-x64 (Intel macOS) and DJL
0.36.0 does not download CPU builds, so the offline models fail to load there (the first embedding
throws `IllegalStateException`). CPU only.

## Verification

`./gradlew :agentweave-discovery-embeddings:build --console=plain` — 33 tests:

- `LangChain4jEmbedderTest` (fake model): query prefix on queries only, document prefix on
  documents only, single batch by default, ordered `batchSize` chunks, empty list without a model
  call, dimension asked once and cached, explicit dimension not asking the model and enforced,
  blank/null rejected, vectors copied, wrong vector count rejected, a `doEmbed`-only model works,
  default naming.
- `OfflineEmbeddersConfigTest` (fake loader): bge prefix on queries only (exact captured inputs),
  MiniLM unprefixed, no load from factory or `dimension()`, 16 concurrent first calls load once,
  failed load reported and retried, shared instances, missing-artifact message.
- `OfflineModelsTest` (both real models): dimension 384; identical vectors for identical input;
  for three query/paraphrase/unrelated triples (e.g. "list files in a directory" vs "List the
  contents of a folder" vs "Get the current weather") the paraphrase has the higher cosine
  similarity; batched equals single; 8 threads give the sequential vectors; bge query and document
  vectors differ for the same text, MiniLM's do not. First-load and per-embedding latency are
  printed to the test report's system-out.
- `OptionalModelArtifactsTest`: in a class loader without the MiniLM jar only `allMiniLmL6V2()`
  fails; without any in-process jar `bgeSmallEnV15()` fails clearly and `LangChain4jEmbedder` works.

## Sources

- LangChain4j 1.23.0 `EmbeddingModel`, `DimensionAwareEmbeddingModel` (sources jar, Maven Central).
- LangChain4j in-process embeddings 1.23.0-beta33 — `AbstractInProcessEmbeddingModel`,
  `OnnxBertBiEncoder`, `BgeSmallEnV15QuantizedEmbeddingModel`,
  `AllMiniLmL6V2QuantizedEmbeddingModel` (sources jars, Maven Central):
  <https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-embeddings/1.23.0-beta33/>
- bge-small-en-v1.5 model card (MIT, query instruction): <https://huggingface.co/BAAI/bge-small-en-v1.5>
- all-MiniLM-L6-v2 model card (Apache-2.0, 256-word-piece truncation):
  <https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2>
