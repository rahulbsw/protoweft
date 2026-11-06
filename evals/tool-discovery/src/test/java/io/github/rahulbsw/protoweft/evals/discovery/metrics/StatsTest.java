package io.github.rahulbsw.protoweft.evals.discovery.metrics;

import io.github.rahulbsw.protoweft.evals.discovery.metrics.Stats.Estimate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatsTest {

    @Test
    void wilsonMatchesReferenceValues() {
        Estimate e = Stats.wilson(8, 10);
        assertThat(e.mean()).isEqualTo(0.8);
        assertThat(e.low()).isCloseTo(0.4902, within(1e-4));
        assertThat(e.high()).isCloseTo(0.9433, within(1e-4));
        Estimate zero = Stats.wilson(0, 10);
        assertThat(zero.low()).isZero();
        assertThat(zero.high()).isCloseTo(0.2775, within(1e-4));
        assertThat(Stats.wilson(0, 0).n()).isZero();
    }

    @Test
    void bootstrapIsDeterministicAndBracketsTheMean() {
        double[] v = {0.1, 0.4, 0.35, 0.8, 0.9, 0.2, 0.5, 0.6};
        Estimate a = Stats.bootstrapMean(v);
        Estimate b = Stats.bootstrapMean(v);
        assertThat(a).isEqualTo(b);
        assertThat(a.mean()).isCloseTo(0.48125, within(1e-12));
        assertThat(a.low()).isLessThan(a.mean()).isGreaterThanOrEqualTo(0.1);
        assertThat(a.high()).isGreaterThan(a.mean()).isLessThanOrEqualTo(0.9);
        assertThat(a.method()).isEqualTo("bootstrap");
    }

    @Test
    void bootstrapOfConstantHasZeroWidth() {
        Estimate e = Stats.bootstrapMean(new double[] {3, 3, 3});
        assertThat(e.low()).isEqualTo(3.0);
        assertThat(e.high()).isEqualTo(3.0);
    }

    @Test
    void pairedDifference() {
        Estimate d = Stats.pairedDifference(new double[] {1, 1, 1, 0}, new double[] {0, 0, 1, 0});
        assertThat(d.mean()).isEqualTo(0.5);
        assertThat(d.low()).isGreaterThanOrEqualTo(0.0);
        assertThat(d.high()).isLessThanOrEqualTo(1.0);
        assertThat(d.method()).isEqualTo("paired-bootstrap");
    }
}
