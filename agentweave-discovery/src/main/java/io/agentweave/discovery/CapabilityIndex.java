package io.agentweave.discovery;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Ranks capabilities for a query. The catalog applies exact filters through the candidate
 * predicate, so an index only ranks what may be returned. The default is {@link HybridIndex}.
 */
public interface CapabilityIndex {

    /** Searchable fields of a capability, with their keyword weight. */
    enum Field {
        NAME(3), SOURCE(1), TITLE(2), SUMMARY(2), TAGS(2), PARAMETERS(1), DESCRIPTION(1);

        private final int weight;

        Field(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    /** The text of one capability, by field. */
    record Document(String id, Map<Field, String> fields) {
        public Document {
            Objects.requireNonNull(id, "id");
            fields = Map.copyOf(fields);
        }
    }

    /**
     * One ranked capability.
     *
     * @param id           capability name
     * @param score        fused score (higher is better)
     * @param keywordRank  1-based keyword rank, or 0 if no query term matched
     * @param keywordScore BM25 score
     * @param coverage     fraction of distinct query terms present in the capability
     * @param vectorRank   1-based vector rank, or 0 without vectors (or below the floor)
     * @param cosine       cosine similarity, or {@code NaN} without vectors
     */
    record Hit(String id, double score, int keywordRank, double keywordScore, double coverage,
               int vectorRank, double cosine) {
    }

    /**
     * @param hits     best first
     * @param semantic whether vector similarity contributed
     */
    record Result(List<Hit> hits, boolean semantic) {
        public Result {
            hits = List.copyOf(hits);
        }
    }

    /** Adds or replaces documents. */
    void upsert(Collection<Document> documents);

    void remove(Collection<String> ids);

    /**
     * Ranks the candidates for {@code query}.
     *
     * @param candidates which documents may be returned
     * @param limit      at most this many hits
     * @param minCosine  vector-only matches below this similarity are dropped
     */
    Result search(String query, Predicate<String> candidates, int limit, double minCosine);

    /** Whether this index uses vector similarity. */
    boolean semantic();
}
