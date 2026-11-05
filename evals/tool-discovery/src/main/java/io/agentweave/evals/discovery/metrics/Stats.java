package io.agentweave.evals.discovery.metrics;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Means with 95% confidence intervals.
 *
 * <ul>
 *   <li><b>Proportions</b> from independent 0/1 outcomes (one per task): the <b>Wilson score
 *       interval</b> (z = 1.959964). It behaves well near 0 and 1 and for small n, unlike the
 *       normal approximation.</li>
 *   <li><b>Everything else</b> (nDCG, recall, tokens, latency, cost, and proportions averaged over
 *       repeated trials of the same task): the <b>percentile bootstrap</b> over per-task values,
 *       {@value #BOOTSTRAP_RESAMPLES} resamples with a fixed seed, so reports are reproducible.
 *       Callers aggregate trials per task first, so the resampling unit is the task.</li>
 *   <li><b>Arm differences</b>: a <b>paired</b> percentile bootstrap of the mean per-task
 *       difference (same tasks, same catalog); a CI that contains 0 means no difference was
 *       detected at this sample size — it is not evidence that the arms are equal.</li>
 * </ul>
 */
public final class Stats {

    public static final int BOOTSTRAP_RESAMPLES = 2000;
    public static final long BOOTSTRAP_SEED = 20250301L;
    private static final double Z95 = 1.959963984540054;

    private Stats() {
    }

    /** A mean with a 95% interval; {@code method} names how the interval was computed. */
    public record Estimate(double mean, double low, double high, int n, String method) {

        public static Estimate empty() {
            return new Estimate(Double.NaN, Double.NaN, Double.NaN, 0, "none");
        }
    }

    /** Wilson score interval for {@code successes} out of {@code n}. */
    public static Estimate wilson(long successes, long n) {
        if (n <= 0) {
            return Estimate.empty();
        }
        double p = (double) successes / n;
        double z2 = Z95 * Z95;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new Estimate(p, Math.max(0, center - half), Math.min(1, center + half), (int) n, "wilson");
    }

    /** Wilson interval for 0/1 values. */
    public static Estimate wilson(List<Boolean> outcomes) {
        long s = outcomes.stream().filter(Boolean::booleanValue).count();
        return wilson(s, outcomes.size());
    }

    /** Percentile bootstrap of the mean. */
    public static Estimate bootstrapMean(double[] values) {
        int n = values.length;
        if (n == 0) {
            return Estimate.empty();
        }
        double mean = mean(values);
        if (n == 1) {
            return new Estimate(mean, mean, mean, 1, "bootstrap");
        }
        Random rnd = new Random(BOOTSTRAP_SEED);
        double[] means = new double[BOOTSTRAP_RESAMPLES];
        for (int b = 0; b < BOOTSTRAP_RESAMPLES; b++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += values[rnd.nextInt(n)];
            }
            means[b] = sum / n;
        }
        Arrays.sort(means);
        return new Estimate(mean, percentile(means, 0.025), percentile(means, 0.975), n, "bootstrap");
    }

    /** Paired bootstrap of mean(a − b); {@code a} and {@code b} are aligned per task. */
    public static Estimate pairedDifference(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("paired samples must have equal length");
        }
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            d[i] = a[i] - b[i];
        }
        Estimate e = bootstrapMean(d);
        return new Estimate(e.mean(), e.low(), e.high(), e.n(), "paired-bootstrap");
    }

    public static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? Double.NaN : sum / values.length;
    }

    /** Linear-interpolated percentile of sorted values (q in [0,1]). */
    static double percentile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }
}
