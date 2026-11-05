package io.agentweave.discovery;

import java.util.List;

/**
 * How sure the search is that its top hits are relevant. Raw BM25 scores aren't comparable
 * across queries, so confidence is built from relative signals only.
 *
 * @param level     the overall judgement
 * @param coverage  fraction of query terms found in the top hit (0 if no term matched)
 * @param margin    relative gap between the top two fused scores, {@code (s1 - s2) / s1}
 * @param agreement whether keyword and vector rankings agree on the top hit (always
 *                  {@code false} without vectors)
 * @param cosine    similarity of the top hit, or {@code NaN} without vectors
 */
public record Confidence(Level level, double coverage, double margin, boolean agreement, double cosine) {

    public enum Level { HIGH, MEDIUM, LOW }

    /**
     * Thresholds turning signals into a {@link Level}. The defaults are provisional: tune
     * them on held-out queries for your catalog (the evaluation harness reports them).
     *
     * @param highCoverage  keyword coverage at or above which a hit counts as strong
     * @param lowCoverage   keyword coverage below which a hit counts as weak
     * @param highCosine    similarity at or above which a vector hit counts as strong
     * @param lowCosine     similarity below which a vector hit counts as weak
     * @param minMargin     margin at or above which the top hit stands out
     */
    public record Policy(double highCoverage, double lowCoverage, double highCosine, double lowCosine,
                         double minMargin) {

        public static final Policy DEFAULT = new Policy(0.6, 0.34, 0.70, 0.50, 0.05);

        Confidence assess(List<CapabilityIndex.Hit> hits, boolean semantic) {
            if (hits.isEmpty()) {
                return new Confidence(Level.LOW, 0, 0, false, Double.NaN);
            }
            CapabilityIndex.Hit top = hits.get(0);
            double margin = hits.size() < 2 || top.score() <= 0 ? 1
                    : (top.score() - hits.get(1).score()) / top.score();
            boolean agreement = semantic && top.keywordRank() == 1 && top.vectorRank() == 1;
            double coverage = top.coverage();
            Level level;
            if (!semantic) {
                if (coverage < lowCoverage) {
                    level = Level.LOW;
                } else if (coverage >= highCoverage && margin >= minMargin) {
                    level = Level.HIGH;
                } else {
                    level = Level.MEDIUM;
                }
            } else {
                boolean strongVector = top.cosine() >= highCosine;
                boolean weakVector = Double.isNaN(top.cosine()) || top.cosine() < lowCosine;
                if (coverage < lowCoverage && weakVector) {
                    level = Level.LOW;
                } else if ((agreement && (strongVector || coverage >= highCoverage))
                        || (strongVector && coverage >= highCoverage)) {
                    level = Level.HIGH;
                } else {
                    level = Level.MEDIUM;
                }
            }
            return new Confidence(level, coverage, margin, agreement, top.cosine());
        }
    }
}
