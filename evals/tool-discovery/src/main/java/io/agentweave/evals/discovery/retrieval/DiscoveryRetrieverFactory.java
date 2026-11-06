package io.agentweave.evals.discovery.retrieval;

import io.agentweave.discovery.Embedder;
import io.agentweave.discovery.FallbackPolicy;
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
 */
public final class DiscoveryRetrieverFactory implements RetrieverFactory {

    public static final String BM25 = "catalog-bm25";
    public static final String DENSE = "catalog-dense";
    public static final String HYBRID = "catalog-hybrid";

    private static final Map<String, Embedder> EMBEDDERS = new ConcurrentHashMap<>();

    @Override
    public Set<String> names() {
        return Set.of(BM25, DENSE, HYBRID);
    }

    @Override
    public Retriever create(String name, Map<String, String> options) {
        return catalogRetriever(name, options);
    }

    /** Builds a {@link CatalogRetriever} by name (also used by the discovery arms). */
    public static CatalogRetriever catalogRetriever(String name, Map<String, String> options) {
        FallbackPolicy fallback = fallback(options.getOrDefault("fallback", "none"));
        if (options.containsKey("min-cosine")) {
            double floor = Double.parseDouble(options.get("min-cosine"));
            fallback = new FallbackPolicy(fallback.mode(), fallback.broadenFactor(), floor, Math.min(floor,
                    fallback.broadCosine()), fallback.namesOnlyLimit(), fallback.confidence());
        }
        boolean instruction = Boolean.parseBoolean(options.getOrDefault("instruction", "false"));
        String cache = options.getOrDefault("embedding-cache", "build/embedding-cache");
        return switch (name) {
            case BM25 -> new CatalogRetriever(name, CatalogRetriever.Ranking.KEYWORD, null, fallback, instruction);
            case DENSE -> new CatalogRetriever(name, CatalogRetriever.Ranking.DENSE,
                    embedder(OfflineEmbedders::bgeSmallEnV15, cache), fallback, instruction);
            case HYBRID -> new CatalogRetriever(name, CatalogRetriever.Ranking.HYBRID,
                    embedder(OfflineEmbedders::bgeSmallEnV15, cache), fallback, instruction);
            default -> throw new IllegalArgumentException("unknown catalog retriever: " + name);
        };
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
