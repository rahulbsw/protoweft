package io.github.rahulbsw.protoweft.discovery;

import java.util.Objects;

/**
 * What a search does when its {@link Confidence} is {@link Confidence.Level#LOW}, so that
 * relevant capabilities are not silently omitted. Every result reports the stage it used.
 *
 * @param mode           the strategy
 * @param broadenFactor  {@link Mode#PROGRESSIVE}: multiply the limit by this when broadening
 * @param minCosine      vector-only matches below this similarity are dropped normally
 * @param broadCosine    the relaxed floor used when broadening
 * @param namesOnlyLimit {@link Mode#PROGRESSIVE}: at most this many names in the names-only
 *                       list ({@code 0} disables that stage)
 * @param confidence     thresholds for judging confidence
 */
public record FallbackPolicy(Mode mode, int broadenFactor, double minCosine, double broadCosine, int namesOnlyLimit,
                             Confidence.Policy confidence) {

    public enum Mode {
        /** Broaden the search; if still low, add a names-and-summaries list without schemas. */
        PROGRESSIVE,
        /** On low confidence return every filter-passing capability with its schema (the baseline). */
        ALL_SCHEMAS,
        /** Never broaden; low confidence is only reported. */
        NONE
    }

    /** Stage a result was produced at. */
    public enum Stage { NONE, BROADENED, NAMES_ONLY, ALL_SCHEMAS }

    public static final FallbackPolicy DEFAULT =
            new FallbackPolicy(Mode.PROGRESSIVE, 3, 0.30, 0.0, 50, Confidence.Policy.DEFAULT);

    public FallbackPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(confidence, "confidence");
        if (broadenFactor < 1 || namesOnlyLimit < 0) {
            throw new IllegalArgumentException("broadenFactor must be >= 1 and namesOnlyLimit >= 0");
        }
    }

    public static FallbackPolicy of(Mode mode) {
        return new FallbackPolicy(mode, DEFAULT.broadenFactor, DEFAULT.minCosine, DEFAULT.broadCosine,
                DEFAULT.namesOnlyLimit, DEFAULT.confidence);
    }

    public FallbackPolicy withConfidence(Confidence.Policy policy) {
        return new FallbackPolicy(mode, broadenFactor, minCosine, broadCosine, namesOnlyLimit, policy);
    }
}
