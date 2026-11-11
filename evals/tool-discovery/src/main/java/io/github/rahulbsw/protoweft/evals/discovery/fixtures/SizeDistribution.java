package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import java.util.List;
import java.util.Random;

/**
 * The empirical distribution of recorded result sizes (in tokens). A fixture miss samples a target
 * size from this distribution so the synthetic payload is sized like a real result, never a token-
 * free stub that would silently understate result bloat. Sampling is empirical (draw one observed
 * size), which needs no distributional assumption.
 */
public final class SizeDistribution {

    private final int[] sizes;
    private final int fallback;

    private SizeDistribution(int[] sizes, int fallback) {
        this.sizes = sizes;
        this.fallback = fallback;
    }

    /**
     * A distribution over the given observed token sizes. {@code fallback} is the target used when
     * no sizes were observed (an empty fixture set), so a miss is still realistically sized.
     */
    public static SizeDistribution of(List<Integer> observed, int fallback) {
        if (fallback <= 0) {
            throw new IllegalArgumentException("fallback must be positive");
        }
        int[] a = observed.stream().filter(s -> s > 0).mapToInt(Integer::intValue).toArray();
        return new SizeDistribution(a, fallback);
    }

    public boolean isEmpty() {
        return sizes.length == 0;
    }

    /** A target token size: one drawn observation, or the fallback when none were observed. */
    public int sample(Random random) {
        return sizes.length == 0 ? fallback : sizes[random.nextInt(sizes.length)];
    }
}
