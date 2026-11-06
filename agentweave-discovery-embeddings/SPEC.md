---
id: agentweave-discovery-embeddings
type: module-design
status: draft
title: agentweave-discovery-embeddings — offline embedders on ONNX Runtime
parent: agentweave-architecture
---

# agentweave-discovery-embeddings

## Responsibility

Supplies an offline implementation of the `io.agentweave.discovery.Embedder` SPI (owned by
`agentweave-discovery`), so discovery can rank capabilities semantically with no API key, network
call or model download: quantized BAAI bge-small-en-v1.5 (384 dimensions) running in-process on
the CPU through ONNX Runtime, tokenized by a pure-Java BERT WordPiece tokenizer.

This spec also owns `agentweave-model-bge-small-en-v15`, the published artifact that carries the
model file and its vocabulary as classpath resources (see "The model artifact").

Embedding with any LangChain4j `EmbeddingModel` (hosted or in-process) is `LangChain4jEmbedder`
in `agentweave-langchain4j` (see that module's spec).

## Boundary

- Runtime dependencies: `agentweave-discovery` (`api`), `com.microsoft.onnxruntime:onnxruntime:1.23.2`
  (`implementation`: no ONNX Runtime type is in the public API) and
  `io.agentweave:agentweave-model-bge-small-en-v15` (`runtimeOnly`, see "Dependency scope").
  Nothing else: no LangChain4j, no DJL. No dependency on any protocol or integration module.
- Does not modify the `Embedder` SPI or store vectors.
- Public API (package `io.agentweave.discovery.embeddings`):
  - `OfflineEmbedders` — `bgeSmallEnV15()`; constants `BGE_QUERY_PREFIX`,
    `BGE_SMALL_EN_V15_NAME` (`"bge-small-en-v1.5-q"`; confidence presets in
    `agentweave-discovery` and the eval embedding cache key off it, so it never changes while the
    vectors stay the same).
  - `WordPieceTokenizer` — `of(List<String>)`, `read(InputStream vocabTxt)`; `tokenize`,
    `tokenIds` (word pieces, no special tokens, never truncated), `encode(text, maxLength)`
    (`[CLS] A [SEP]`), `encodePair(first, second, maxLength)` (`[CLS] A [SEP] B [SEP]`, token
    types 0/1, longest-first truncation); `vocabularySize`, `token(id)`, `id(token)`;
    `Encoding` with `length`, `ids`, `typeIds`. Public so a cross-encoder reranker can share it.
- Package-private: `OnnxModel` (one session over a BERT-style model: `input_ids`,
  `attention_mask`, optional `token_type_ids`; returns the first output's vector at position 0,
  i.e. the `[CLS]` hidden state of an encoder or the logits of a classifier, so a reranker can
  reuse it), `BertBiEncoder`, `OfflineEmbedder`, `LazyEncoder`, `BertCharacters`.

```java
Embedder offline = OfflineEmbedders.bgeSmallEnV15();
```

## The model artifact

`io.agentweave:agentweave-model-bge-small-en-v15` — published, in the BOM, a `java-library` with
no classes (Automatic-Module-Name `io.agentweave.model.bge.small.en.v15`). Resources under
`io/agentweave/models/bge-small-en-v15/` (a directory name that is not a Java package, so a named
module never encapsulates it):

| Resource | Content |
|---|---|
| `model.onnx` | `Xenova/bge-small-en-v1.5/onnx/model_quantized.onnx`, 34,014,426 bytes, SHA-256 `6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4` (the Hugging Face LFS id of that file) |
| `vocab.txt` | 30,522 tokens, one per line, line number = id; SHA-256 `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3`; byte-identical to `BAAI/bge-small-en-v1.5/vocab.txt` (same git blob id, `fb140275…`) |
| `LICENSE` | MIT, from FlagEmbedding (the model card declares MIT and links it) |
| `NOTICE` | Origin of both files and their checksums |

- **No binaries in git.** The build's `extractModel` task resolves
  `dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:1.23.0-beta33` from Maven Central
  through its own non-transitive configuration (so LangChain4j's code and dependencies never reach
  any classpath, and the jar is covered by dependency verification), takes
  `bge-small-en-v1.5-q.onnx` and `bge-small-en-v1.5-q-tokenizer.json`, checks both full SHA-256s,
  writes `vocab.txt` from the tokenizer's `model.vocab` (ids contiguous from 0, no line breaks in
  tokens, exactly 30,522 entries) and checks its SHA-256 too. Any mismatch fails the build. The
  output goes into the jar through `processResources`, not a source directory, so the sources jar
  carries only `LICENSE` and `NOTICE`.
- That tokenizer.json is byte-identical to the one in both `BAAI/bge-small-en-v1.5` and
  `Xenova/bge-small-en-v1.5` (git blob `688882a7…`), and its vocabulary equals LangChain4j's
  `bert-vocabulary-en.txt` (bert-base-uncased) entry for entry.

## Decisions

- **ONNX Runtime alone, tokenizer in Java.** LangChain4j's in-process models bring
  `langchain4j-embeddings`, `langchain4j-core` (with Jackson) and DJL (a native Rust tokenizer
  plus JNA, Gson, commons-compress/-codec/-io): 16 third-party jars besides ONNX Runtime, and DJL
  ships no tokenizer native for Intel macOS. The BERT tokenizer is a few hundred lines of Java,
  so the module now needs one third-party library.
- **Tokenizer semantics: HuggingFace Rust `tokenizers` (v0.20.3), as configured by the bge
  `tokenizer.json`** (`BertNormalizer` with `clean_text`, `handle_chinese_chars`,
  `strip_accents: null`, `lowercase: true`; `BertPreTokenizer`; `WordPiece` with `[UNK]`, `##`,
  `max_input_chars_per_word` 100; `TemplateProcessing` `[CLS] $A [SEP]` / `[CLS] $A [SEP] $B:1
  [SEP]:1`). In order:
  1. Special tokens `[PAD] [UNK] [CLS] [SEP] [MASK]` (the `added_tokens`, all `normalized:
     false`) are matched literally, case-sensitively, in the raw text, leftmost first; the text
     between them is processed separately. So text containing `[SEP]` gets a real separator, as
     in HuggingFace.
  2. Clean: drop U+0000, U+FFFD and `is_other` characters (Cc, Cf, Co) except tab/LF/CR; map
     White_Space characters to a space. A lone UTF-16 surrogate is dropped too (it becomes U+FFFD
     on the way into Rust).
  3. Pad CJK ideographs (4E00–9FFF, 3400–4DBF, 20000–2A6DF, 2A700–2B73F, 2B740–2B81F,
     2B920–2CEAF, F900–FAFF, 2F800–2FA1F) with spaces.
  4. Strip accents (`strip_accents` defaults to `lowercase`): NFD, then drop Mn, *before*
     lowercasing; then lowercase per character (U+0130 → `i̇`).
  5. Split on White_Space (removed), then isolate each punctuation character (ASCII 33–47, 58–64,
     91–96, 123–126, or Unicode P*).
  6. WordPiece: greedy longest match first, continuation pieces prefixed `##`; a word over 100
     characters (code points) or with any unmatched remainder becomes one `[UNK]`.
  - **Character classes are pinned, not taken from the JDK.** `is_other`, `is_punctuation` and
    `is_mark_nonspacing` come from the `unicode_categories` 0.1.1 crate, whose tables are Unicode
    8.0; the JDK's categories differ for 20 (Cc/Cf/Co), 129 (P*) and 420 (Mn) code points (JDK
    21). `BertCharacters` holds the crate's tables as code point ranges (generated from its
    `tables.rs`), so results do not depend on the JDK version. NFD and lowercasing use the JDK;
    they can differ from Rust only for characters whose decomposition or case mapping is newer
    than the JDK's Unicode version.
  - **Truncation.** `encode` keeps the first `maxLength - 2` pieces. `encodePair` follows
    `truncate_encodings` `LongestFirst` with `n = maxLength - 3`: with `n1` the shorter part's
    length, if `n1 > n / 2` both are cut (shorter to `n / 2`, longer to `n / 2 + n % 2`),
    otherwise only the longer, to `n - n1`.
- **Embedding.** `[CLS] pieces [SEP]` truncated to 512 positions, inputs `input_ids`,
  `attention_mask` (all 1), `token_type_ids` (all 0) as int64 `[1, L]`; the `[CLS]` vector of
  `last_hidden_state` (CLS pooling, as bge prescribes), L2-normalized in float. This reproduces
  LangChain4j's `OnnxBertBiEncoder` bit for bit (see Verification).
  - **Long text is truncated, not windowed.** `OnnxBertBiEncoder` contains code that splits
    text over 510 tokens into windows and averages them by token count, but the DJL tokenizer it
    creates (`HuggingFaceTokenizer.newInstance(json, {padding: false})`) defaults to truncation
    `LONGEST_FIRST` at 512, so its token list never exceeds 512 and one window is all it ever
    embeds. Measured: LangChain4j gives the same vector for 510 and for 600 repetitions of a word.
    Truncating here keeps the vectors identical to today's (eval caches, calibrated presets) and
    matches sentence-transformers' bge setup (`max_seq_length` 512). Capability documents are
    expected to be far shorter.
  - **One sequence per model run; no padded batches.** The quantized model computes activation
    scales over the whole input tensor (dynamic quantization), so padding and batch neighbours
    change every vector: a padded batch of three short texts gave cosine 0.990–0.997 against
    single runs. `embedDocuments` therefore runs each text alone, spread over the common
    fork-join pool; its vectors equal `embedDocument`'s exactly, and a batch of 100 short texts
    runs 2–3× faster than one by one on 14 cores. ONNX Runtime's own intra-op threading is
    left at its default, which keeps single queries fast.
  - Text that yields no tokens (only control characters, say) is rejected with
    `IllegalArgumentException`, as LangChain4j does.
- **Session and loading.** One process-wide `OrtEnvironment` (ONNX Runtime's singleton), one
  session per model with default options, created from the model bytes read from the classpath
  (the loader of `OfflineEmbedders`). The factory and `dimension()` (fixed at 384) load nothing;
  the first embedding reads the vocabulary and creates the session under double-checked locking,
  exactly once even under concurrent first use. A failed load is rethrown as
  `IllegalStateException("Could not load embedding model 'bge-small-en-v1.5-q': …")` and retried
  on the next call. The factory returns one shared instance per class loader and throws
  `IllegalStateException` naming `io.agentweave:agentweave-model-bge-small-en-v15:<version>` when
  the model resource is missing. Tensors and run results are closed after every call; the
  session lives as long as the class loader.
- **Dependency scope.** The model artifact is `runtimeOnly`: it has no classes, so it has no
  place on any compile classpath, yet it is still a transitive runtime dependency (Maven
  `runtime` scope), so `bgeSmallEnV15()` works with no further setup, as before. Users who never
  embed offline can exclude it.
- **Prefix handling.** `embedQuery` prepends `BGE_QUERY_PREFIX` (`"Represent this sentence for
  searching relevant passages: "`, from the model card); documents are embedded as given.
- **Input and output checks.** Null text → `NullPointerException`, blank text →
  `IllegalArgumentException` (checked before the prefix is added, and for every element of
  `embedDocuments` before any work). Every vector is checked against the dimension.
- **One offline model.** all-MiniLM-L6-v2 was dropped (user decision): bge is the default and
  the only model the evals calibrate. `Confidence.Policy.ALL_MINILM_L6_V2` in
  `agentweave-discovery` is removed separately.

## Licenses and sizes

Runtime dependencies of this module (third-party and model):

| Artifact | License | Jar size |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime:1.23.2` | MIT | 75.3 MB |
| `io.agentweave:agentweave-model-bge-small-en-v15` (BAAI bge-small-en-v1.5, quantized by Xenova) | MIT | 24.5 MB |
| `agentweave-discovery-embeddings` itself | MIT | 0.03 MB |

Before this change the same module brought 127.5 MB: ONNX Runtime 75.3, LangChain4j's bge jar
24.6, DJL tokenizers 18.7 + api 1.0, Jackson 2.4, JNA 2.0, commons-compress/-codec/-io 2.0,
`langchain4j-core` 0.8, `langchain4j-embeddings` 0.3, Gson and three small jars 0.4.

ONNX Runtime bundles CPU natives for linux-x64, linux-aarch64, osx-x64, osx-aarch64 and win-x64
and extracts them on first use; nothing else is native, so the platforms are ONNX Runtime's. Tested
on osx-aarch64. CPU only.

## Verification

`./gradlew :agentweave-discovery-embeddings:build` (the corpus tests are skipped unless the
discovery eval datasets are present: `evals/tool-discovery/build/data`, or
`-PparityData=<dir>`). The references are test-only dependencies: LangChain4j's
`BgeSmallEnV15QuantizedEmbeddingModel` (same ONNX file) and DJL 0.36.0's binding of the Rust
tokenizer over the same `tokenizer.json`.

- `TokenizerParityTest` — ids equal DJL's for:
  - 73 tricky texts (accents, Greek final sigma, Turkish İ, ligatures, CJK incl. extension B and
    compatibility ideographs, RTL scripts with marks, emoji sequences, camelCase/snake/kebab, URLs,
    e-mail, Windows paths, currency, punctuation runs, smart quotes, control characters, NBSP and
    other White_Space, zero-width and BOM, soft hyphen, U+FFFD, private use, lone surrogates,
    100/101-character words, literal special tokens, characters whose category changed after
    Unicode 8, texts of 509–3,000 words);
  - every distinct JSON string of the MCPToolBench++ and ToolRet files: **102,087 texts, 11.55
    million word pieces, 0 mismatches** both untruncated and as model input (truncated to 512
    with special tokens);
  - single-text truncation and pairs (types included) at `maxLength` 3, 4, 9, 16, 17, 64 and 512
    over the tricky texts plus 2,000 dataset texts: **14,511 single texts and 14,511 pairs, 0
    mismatches**.
- `EmbeddingParityTest` — against LangChain4j (queries prefixed on both sides): 73 tricky texts
  as documents and as queries, 3,000 sampled dataset texts as documents, 500 as queries, and 300
  dataset texts over 510 tokens: **all vectors bit-identical (min cosine 1.0)**. Texts over the
  window: 600 words give the same vector as 510, for both implementations.
- `OfflineModelsTest` (real model): dimension 384 and unit norm; identical vectors for identical
  input; paraphrase above unrelated text for three triples; `embedDocuments` equals
  `embedDocument` exactly on 78 texts including long and tricky ones; 8 threads each embedding 24
  texts singly and as a batch give the sequential vectors; query and document vectors differ;
  token-less text rejected.
- `OfflineEmbeddersConfigTest` (fake encoder): prefix on queries only (exact inputs), order kept
  and bad input rejected before any work in `embedDocuments`, blank/null/wrong-dimension
  rejected, no load from factory or `dimension()`, 16 concurrent first calls load once, failed
  load reported and retried, shared instance, missing-resource message.
- `MissingModelArtifactTest`: in a class loader without the model artifact, `bgeSmallEnV15()`
  fails naming the coordinates and the resource.
- `WordPieceTokenizerTest`: vocabulary ids, normalization and splitting examples, the
  100-character rule, `encode`/`encodePair` layout, the longest-first cases, greedy matching on a
  toy vocabulary.
- `HybridCatalogTest`: hybrid search finds a paraphrase that keyword search misses.

Latency (Apple M4 Pro, 14 cores, JDK 21, a fresh JVM per run, three runs each, the same 100
short tool documents): first embedding including model load 0.74–1.16 s here vs 4.9 s for
LangChain4j; per embedding 1.4 ms vs 1.5–1.6 ms; a batch of 100 55–59 ms vs 55–73 ms. The model
work is the same ONNX session either way; the load difference was not profiled (LangChain4j also
loads DJL's native tokenizer and parses `tokenizer.json`).

## Sources

- HuggingFace `tokenizers` v0.20.3: `tokenizers/src/normalizers/bert.rs`,
  `pre_tokenizers/bert.rs`, `models/wordpiece/mod.rs` (`tokenize`), `utils/truncation.rs`
  (`truncate_encodings`), `tokenizer/added_vocabulary.rs`, `tokenizer/mod.rs` (max length minus
  added tokens): <https://github.com/huggingface/tokenizers/tree/v0.20.3/tokenizers/src>
- `unicode_categories` 0.1.1 (`src/lib.rs`, `src/tables.rs`):
  <https://crates.io/crates/unicode_categories/0.1.1>
- LangChain4j in-process embeddings 1.23.0-beta33 — `OnnxBertBiEncoder`,
  `AbstractInProcessEmbeddingModel`, `BgeSmallEnV15QuantizedEmbeddingModel` (sources jars):
  <https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-embeddings/1.23.0-beta33/>
- DJL 0.36.0 `HuggingFaceTokenizer` defaults (truncation `LONGEST_FIRST`, max length 512),
  observed through its API.
- bge-small-en-v1.5 model card (MIT, query instruction, CLS pooling):
  <https://huggingface.co/BAAI/bge-small-en-v1.5>; FlagEmbedding license:
  <https://github.com/FlagOpen/FlagEmbedding/blob/master/LICENSE>
- Quantized export and file ids: <https://huggingface.co/Xenova/bge-small-en-v1.5>
  (`/api/models/Xenova/bge-small-en-v1.5/tree/main/onnx` lists the LFS SHA-256).
