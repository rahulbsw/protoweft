package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a capability search. Besides the hits it states how it was produced, so
 * nothing is omitted silently: the confidence, the fallback stage, and how many matching
 * capabilities were hidden as unavailable or not permitted.
 *
 * @param hits               best first
 * @param confidence         how relevant the top hits look
 * @param stage              the fallback stage used
 * @param namesOnly          at {@link FallbackPolicy.Stage#NAMES_ONLY}: further candidates as
 *                           cards, without schemas
 * @param hiddenUnavailable  per source: matching capabilities hidden because the source is
 *                           unavailable
 * @param hiddenByPermission matching capabilities the caller may not see (count only)
 * @param mode               whether ranking used vectors
 * @param catalogSize        capabilities in the catalog
 */
public record DiscoveryResult(List<Hit> hits, Confidence confidence, FallbackPolicy.Stage stage,
                              List<CapabilityCard> namesOnly, Map<String, Integer> hiddenUnavailable,
                              int hiddenByPermission, Mode mode, int catalogSize) {

    public enum Mode { KEYWORD_ONLY, HYBRID }

    /**
     * One returned capability.
     *
     * @param card   its concise metadata
     * @param score  fused relevance score (only comparable within this result)
     * @param schema the full tool spec when inlined, else {@code null}
     */
    public record Hit(CapabilityCard card, double score, ToolSpec schema) {
    }

    public DiscoveryResult {
        hits = List.copyOf(hits);
        namesOnly = List.copyOf(namesOnly);
        hiddenUnavailable = Map.copyOf(hiddenUnavailable);
    }

    public List<String> names() {
        return hits.stream().map(h -> h.card().name()).toList();
    }
}
