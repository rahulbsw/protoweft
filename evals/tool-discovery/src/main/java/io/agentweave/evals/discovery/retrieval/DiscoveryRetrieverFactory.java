package io.agentweave.evals.discovery.retrieval;

import io.agentweave.discovery.Embedder;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.HybridIndex;
import io.agentweave.evals.discovery.rerank.CrossEncoderReranker;
import io.agentweave.discovery.embeddings.OfflineEmbedders;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registers the discovery-backed retrievers (ServiceLoader):
 * <ul>
 *   <li>{@code catalog-bm25} — keyword-only catalog;</li>
 *   <li>{@code catalog-dense} — vector-only ranking with bge-small-en-v1.5;</li>
 *   <li>{@code catalog-hybrid} — the default hybrid catalog with bge-small-en-v1.5.</li>
 * </ul>
 * Options ({@code --retriever-opt}): {@code fallback=none|progressive|all_schemas} (default
 * {@code none}: pure ranking), {@code min-cosine=<x>} (vector floor, default the product's 0.30),
 * {@code instruction=true} (prefix ToolRet instructions), {@code embedding-cache=<dir>|none}
 * (default {@code build/embedding-cache}).
 *
 * <p>Variants: {@code <name>:key=value:key=value} overrides options for one retriever, so a single
 * run can compare tunings side by side, e.g. {@code catalog-hybrid:fusion=convex:alpha=0.7}.
 * Hybrid tuning keys: {@code fusion=rrf|convex}, {@code rrf-k}, {@code kw}, {@code vw} (RRF list
 * weights), {@code alpha} (convex vector weight), {@code describe=true} (embed full descriptions).
 * Without {@code fusion}, hybrid uses the product default ({@link HybridIndex.Fusion#DEFAULT}).
 *
 * <p>Reranking experiment ({@code catalog-hybrid} only; see {@link RerankingIndex}):
 * {@code rerank=l6|l2} (MS MARCO MiniLM cross-encoder, fetched by {@code scripts/fetch_reranker.py}),
 * {@code reranker-dir=<dir>} (default {@code build/models/ms-marco-minilm-<l6|l2>-v2}),
 * {@code rerank-depth=<n>} (default 50), {@code rerank-text=card|description} (default card),
 * {@code rerank-mix=<w>} (blend {@code w * z(reranker) + (1 - w) * z(hybrid)}; default pure
 * reranking), {@code rerank-threads=<n>} (ONNX Runtime intra-op threads per pair; default 0 =
 * its default), e.g. {@code catalog-hybrid:rerank=l6:rerank-depth=20}.
 */
public final class DiscoveryRetrieverFactory implements RetrieverFactory {

    public static final String BM25 = "catalog-bm25";
    public static final String DENSE = "catalog-dense";
    public static final String HYBRID = "catalog-hybrid";

    private static final Map<String, Embedder> EMBEDDERS = new ConcurrentHashMap<>();
    private static final Map<String, CrossEncoderReranker> RERANKERS = new ConcurrentHashMap<>();

    @Override
    public Set<String> names() {
        return Set.of(BM25, DENSE, HYBRID);
    }

    @Override
    public Retriever create(String name, Map<String, String> options) {
        return catalogRetriever(name, options);
    }

    /** Builds a {@link CatalogRetriever} by name (also used by the discovery arms). */
    public static CatalogRetriever catalogRetriever(String name, Map<String, String> baseOptions) {
        Map<String, String> options = new java.util.LinkedHashMap<>(baseOptions);
        String[] parts = name.split(":");
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            options.put(kv[0], kv.length > 1 ? kv[1] : "true");
        }
        String base = parts[0];
        HybridIndex.Fusion fusion = fusion(options);
        boolean describe = Boolean.parseBoolean(options.getOrDefault("describe", "false"));
        FallbackPolicy fallback = fallback(options.getOrDefault("fallback", "none"));
        if (options.containsKey("min-cosine")) {
            double floor = Double.parseDouble(options.get("min-cosine"));
            fallback = new FallbackPolicy(fallback.mode(), fallback.broadenFactor(), floor, Math.min(floor,
                    fallback.broadCosine()), fallback.namesOnlyLimit(), fallback.confidence());
        }
        boolean instruction = Boolean.parseBoolean(options.getOrDefault("instruction", "false"));
        String cache = options.getOrDefault("embedding-cache", "build/embedding-cache");
        CatalogRetriever.Rerank rerank = rerank(options);
        if (rerank != null && !base.equals(HYBRID)) {
            throw new IllegalArgumentException("rerank applies to " + HYBRID + " only: " + name);
        }
        return switch (base) {
            case BM25 -> new CatalogRetriever(name, CatalogRetriever.Ranking.KEYWORD, null, fallback, instruction);
            case DENSE -> new CatalogRetriever(name, CatalogRetriever.Ranking.DENSE,
                    embedder(OfflineEmbedders::bgeSmallEnV15, cache), fallback, instruction, fusion, describe);
            case HYBRID -> new CatalogRetriever(name, CatalogRetriever.Ranking.HYBRID,
                    embedder(OfflineEmbedders::bgeSmallEnV15, cache), fallback, instruction, fusion, describe, rerank);
            default -> throw new IllegalArgumentException("unknown catalog retriever: " + name);
        };
    }

    /** The hybrid fusion named by the options, or the product default. */
    static HybridIndex.Fusion fusion(Map<String, String> o) {
        if (!o.containsKey("fusion")) {
            return HybridIndex.Fusion.DEFAULT;
        }
        String kind = o.get("fusion").toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "rrf" -> HybridIndex.Fusion.rrf(Integer.parseInt(o.getOrDefault("rrf-k", "60")),
                    Double.parseDouble(o.getOrDefault("kw", "1")), Double.parseDouble(o.getOrDefault("vw", "1")));
            case "convex" -> HybridIndex.Fusion.convex(Double.parseDouble(o.getOrDefault("alpha", "0.5")));
            default -> throw new IllegalArgumentException("fusion must be rrf or convex: " + kind);
        };
    }

    /** The reranking named by the options, or {@code null} without {@code rerank}. */
    static CatalogRetriever.Rerank rerank(Map<String, String> o) {
        String model = o.get("rerank");
        if (model == null || model.equalsIgnoreCase("none") || model.equalsIgnoreCase("false")) {
            return null;
        }
        String key = model.toLowerCase(Locale.ROOT);
        if (!key.equals("l6") && !key.equals("l2")) {
            throw new IllegalArgumentException("rerank must be l6 or l2: " + model);
        }
        Path dir = Path.of(o.getOrDefault("reranker-dir", "build/models/ms-marco-minilm-" + key + "-v2"))
                .toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(dir.resolve("model.onnx"))) {
            throw new IllegalArgumentException("no reranker model at " + dir
                    + " (run scripts/fetch_reranker.py or set reranker-dir)");
        }
        int depth = Integer.parseInt(o.getOrDefault("rerank-depth", String.valueOf(RerankingIndex.DEFAULT_DEPTH)));
        RerankingIndex.Text text = RerankingIndex.Text.valueOf(o.getOrDefault("rerank-text", "card")
                .strip().toUpperCase(Locale.ROOT));
        double mix = o.containsKey("rerank-mix") ? Double.parseDouble(o.get("rerank-mix")) : Double.NaN;
        if (!Double.isNaN(mix) && (mix < 0 || mix > 1)) {
            throw new IllegalArgumentException("rerank-mix must be in [0, 1]: " + mix);
        }
        if (depth < 1) {
            throw new IllegalArgumentException("rerank-depth must be at least 1: " + depth);
        }
        int threads = Integer.parseInt(o.getOrDefault("rerank-threads", "0"));
        CrossEncoderReranker reranker = RERANKERS.computeIfAbsent(dir + "|" + threads,
                k -> CrossEncoderReranker.fromDirectory(dir, threads));
        return new CatalogRetriever.Rerank(reranker, depth, text, mix);
    }

    /** {@code none|progressive|all_schemas} to a policy with the product's default thresholds. */
    public static FallbackPolicy fallback(String mode) {
        return FallbackPolicy.of(FallbackPolicy.Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT).replace('-', '_')));
    }

    /** One shared (caching) embedder per model and cache directory, so the cache file is read once. */
    static Embedder embedder(Supplier<Embedder> model, String cacheDir) {
        Embedder base = model.get();
        if (cacheDir.equalsIgnoreCase("none")) {
            return base;
        }
        Path dir = Path.of(cacheDir).toAbsolutePath().normalize();
        return EMBEDDERS.computeIfAbsent(base.name() + "|" + dir, k -> new CachingEmbedder(base, dir));
    }
}
