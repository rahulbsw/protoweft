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

        /** Keyword-only default; also used for embedders without a calibrated preset. */
        public static final Policy DEFAULT = new Policy(0.6, 0.34, 0.70, 0.50, 0.05);

        /** Calibrated for hybrid search with bge-small-en-v1.5 (see the evaluation in the module spec). */
        public static final Policy BGE_SMALL_EN_V15 = new Policy(1.0, 0.9, 0.80, 0.65, 0.05);

        /** Calibrated for hybrid search with all-MiniLM-L6-v2. */
        public static final Policy ALL_MINILM_L6_V2 = new Policy(0.6, 0.3, 0.85, 0.50, 0.05);

        /**
         * The calibrated preset for an embedder (matched by {@link Embedder#name()} prefix), or
         * {@link #DEFAULT} for keyword-only search and unknown embedders.
         */
        public static Policy forEmbedder(Embedder embedder) {
            if (embedder == null) {
                return DEFAULT;
            }
            String name = embedder.name().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("bge-small-en-v1.5")) {
                return BGE_SMALL_EN_V15;
            }
            if (name.startsWith("all-minilm-l6-v2")) {
                return ALL_MINILM_L6_V2;
            }
            return DEFAULT;
        }

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
