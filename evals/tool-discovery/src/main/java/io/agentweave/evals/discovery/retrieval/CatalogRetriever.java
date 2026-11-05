package io.agentweave.evals.discovery.retrieval;

import io.agentweave.discovery.CapabilityIndex;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.Embedder;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.HybridIndex;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A retriever backed by AgentWeave's {@link io.agentweave.discovery.CapabilityCatalog}: the eval
 * catalog is ingested through {@link CatalogBridge} and every query is a real
 * {@code catalog.search}, so ranking, the vector floor and {@link io.agentweave.discovery.Confidence}
 * are exactly the product's. Three rankings:
 * <ul>
 *   <li>{@link Ranking#KEYWORD} — the default {@link HybridIndex} without an embedder (field-weighted BM25);</li>
 *   <li>{@link Ranking#DENSE} — {@link VectorOnlyIndex}: cosine only, below-floor hits dropped;</li>
 *   <li>{@link Ranking#HYBRID} — {@link HybridIndex} with the embedder (BM25 + cosine, RRF k = 60).</li>
 * </ul>
 * The query is the task's query, optionally prefixed by its ToolRet instruction. The catalog is
 * rebuilt only when the eval catalog's fingerprint changes.
 */
public final class CatalogRetriever implements Retriever {

    public enum Ranking { KEYWORD, DENSE, HYBRID }

    /** A ranking plus the product's own account of it. */
    public record Search(List<String> toolIds, DiscoveryResult result) {
    }

    private final String name;
    private final Ranking ranking;
    private final Embedder embedder;
    private final FallbackPolicy fallback;
    private final boolean withInstruction;
    private String fingerprint;
    private CatalogBridge.Built built;

    /**
     * @param embedder        required for {@code DENSE} and {@code HYBRID}, ignored for {@code KEYWORD}
     * @param fallback        the catalog's fallback policy ({@code NONE} for pure ranking metrics)
     * @param withInstruction prefix ToolRet instructions to the query
     */
    public CatalogRetriever(String name, Ranking ranking, Embedder embedder, FallbackPolicy fallback,
                            boolean withInstruction) {
        this.name = Objects.requireNonNull(name, "name");
        this.ranking = Objects.requireNonNull(ranking, "ranking");
        if (ranking != Ranking.KEYWORD && embedder == null) {
            throw new IllegalArgumentException(ranking + " ranking needs an embedder");
        }
        this.embedder = ranking == Ranking.KEYWORD ? null : embedder;
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.withInstruction = withInstruction;
    }

    /** The same retriever with another fallback policy (arms use the product default). */
    public CatalogRetriever withFallback(FallbackPolicy policy) {
        return new CatalogRetriever(name, ranking, embedder, policy, withInstruction);
    }

    @Override
    public String name() {
        return name;
    }

    public Ranking ranking() {
        return ranking;
    }

    public FallbackPolicy fallback() {
        return fallback;
    }

    @Override
    public synchronized void index(EvalCatalog catalog) {
        if (!catalog.fingerprint().equals(fingerprint)) {
            built = null; // release the previous index before building the next
            built = CatalogBridge.build(catalog, indexSupplier(), fallback);
            fingerprint = catalog.fingerprint();
        }
    }

    /** The discovery catalog for an eval catalog (built on first use). */
    public synchronized CatalogBridge.Built bridge(EvalCatalog catalog) {
        index(catalog);
        return built;
    }

    @Override
    public List<String> retrieve(EvalTask task, EvalCatalog catalog, int k) {
        return search(task, catalog, k).toolIds();
    }

    /** Searches the catalog for the task; ranked ids exclude names-only candidates. */
    public Search search(EvalTask task, EvalCatalog catalog, int k) {
        CatalogBridge.Built b = bridge(catalog);
        DiscoveryResult r = b.catalog().search(DiscoveryQuery.builder(queryText(task)).limit(Math.max(1, k)).build());
        List<String> ids = r.names().stream().map(b::toolId).filter(Objects::nonNull).limit(k).toList();
        return new Search(ids, r);
    }

    String queryText(EvalTask task) {
        return withInstruction && task.instructionText().isPresent()
                ? task.instructionText().get().strip() + " " + task.query() : task.query();
    }

    private Supplier<CapabilityIndex> indexSupplier() {
        return switch (ranking) {
            case KEYWORD -> HybridIndex::new;
            case HYBRID -> () -> new HybridIndex(embedder);
            case DENSE -> () -> new VectorOnlyIndex(new HybridIndex(embedder));
        };
    }
}
