package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.discovery.CapabilityIndex;
import io.github.rahulbsw.protoweft.discovery.HybridIndex;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Dense-only ranking for the {@code catalog-dense} retriever: a {@link HybridIndex} with an
 * embedder does the indexing (same embedding text, same normalization, same embedding cache), and
 * its hits are re-ranked by cosine alone, dropping hits below {@code minCosine}. The hits keep
 * their keyword signals (coverage, keyword rank), so {@link io.github.rahulbsw.protoweft.discovery.Confidence}
 * is computed from the same signals as for hybrid search; the fused score is replaced by the
 * cosine. Costs one full ranking of the catalog per query (fine for tens of thousands of tools).
 */
final class VectorOnlyIndex implements CapabilityIndex {

    private final HybridIndex inner;

    VectorOnlyIndex(HybridIndex inner) {
        if (!inner.semantic()) {
            throw new IllegalArgumentException("dense ranking needs an index with an embedder");
        }
        this.inner = inner;
    }

    @Override
    public void upsert(Collection<Document> documents) {
        inner.upsert(documents);
    }

    @Override
    public void remove(Collection<String> ids) {
        inner.remove(ids);
    }

    @Override
    public Result search(String query, Predicate<String> candidates, int limit, double minCosine) {
        List<Hit> hits = inner.search(query, candidates, Integer.MAX_VALUE, minCosine).hits().stream()
                .filter(h -> h.vectorRank() > 0 && !Double.isNaN(h.cosine()) && h.cosine() >= minCosine)
                .sorted(Comparator.comparingInt(Hit::vectorRank))
                .limit(limit)
                .map(h -> new Hit(h.id(), h.cosine(), h.keywordRank(), h.keywordScore(), h.coverage(), h.vectorRank(),
                        h.cosine()))
                .toList();
        return new Result(hits, true);
    }

    @Override
    public boolean semantic() {
        return true;
    }
}
