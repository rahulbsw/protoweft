package io.agentweave.evals.discovery.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.agentweave.evals.discovery.metrics.RetrievalMetrics.completenessAt;
import static io.agentweave.evals.discovery.metrics.RetrievalMetrics.ndcgAt;
import static io.agentweave.evals.discovery.metrics.RetrievalMetrics.precisionAt;
import static io.agentweave.evals.discovery.metrics.RetrievalMetrics.recallAt;
import static io.agentweave.evals.discovery.metrics.RetrievalMetrics.reciprocalRank;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RetrievalMetricsTest {

    // 4 relevant docs (a, b, c, d); d is never retrieved.
    private static final List<String> RANKED = List.of("a", "x", "b", "y", "c");
    private static final Map<String, Integer> QRELS = Map.of("a", 1, "b", 1, "c", 1, "d", 1);

    @Test
    void precisionDividesByKEvenWhenFewerRetrieved() {
        assertThat(precisionAt(RANKED, QRELS, 1)).isEqualTo(1.0);
        assertThat(precisionAt(RANKED, QRELS, 3)).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(precisionAt(RANKED, QRELS, 5)).isCloseTo(0.6, within(1e-12));
        assertThat(precisionAt(RANKED, QRELS, 10)).isCloseTo(0.3, within(1e-12));
    }

    @Test
    void recallOverAllRelevant() {
        assertThat(recallAt(RANKED, QRELS, 1)).isEqualTo(0.25);
        assertThat(recallAt(RANKED, QRELS, 3)).isEqualTo(0.5);
        assertThat(recallAt(RANKED, QRELS, 5)).isEqualTo(0.75);
        assertThat(recallAt(RANKED, QRELS, 100)).isEqualTo(0.75);
    }

    @Test
    void ndcgBinaryHandComputed() {
        // DCG@3 = 1/log2(2) + 0 + 1/log2(4) = 1.5; IDCG@3 = 1 + 1/log2(3) + 1/log2(4) = 2.1309298
        assertThat(ndcgAt(RANKED, QRELS, 3)).isCloseTo(1.5 / (1 + 1 / log2(3) + 0.5), within(1e-12));
        assertThat(ndcgAt(RANKED, QRELS, 3)).isCloseTo(0.703918, within(1e-6));
        assertThat(ndcgAt(RANKED, QRELS, 1)).isEqualTo(1.0);
    }

    @Test
    void ndcgUsesLinearGradedGainLikeTrecEval() {
        List<String> ranked = List.of("x", "b", "a");
        Map<String, Integer> q = Map.of("a", 2, "b", 1);
        // DCG = 1/log2(3) + 2/log2(4) = 1.6309298; IDCG = 2/1 + 1/log2(3) = 2.6309298
        assertThat(ndcgAt(ranked, q, 3)).isCloseTo(0.619906, within(1e-6));
        assertThat(ndcgAt(ranked, q, 10)).isCloseTo(0.619906, within(1e-6));
        assertThat(reciprocalRank(ranked, q)).isEqualTo(0.5);
    }

    @Test
    void reciprocalRankAndZeroCases() {
        assertThat(reciprocalRank(RANKED, QRELS)).isEqualTo(1.0);
        assertThat(reciprocalRank(List.of("x", "y", "z", "a"), QRELS)).isEqualTo(0.25);
        assertThat(reciprocalRank(List.of("x"), QRELS)).isZero();
        assertThat(recallAt(List.of("a"), Map.of(), 1)).isZero();
        assertThat(ndcgAt(List.of("a"), Map.of("a", 0), 1)).isZero();
    }

    @Test
    void completenessNeedsEveryRelevantDocInTopK() {
        assertThat(completenessAt(RANKED, QRELS, 5)).isZero();
        Map<String, Integer> two = Map.of("a", 1, "b", 1);
        assertThat(completenessAt(RANKED, two, 1)).isZero();
        assertThat(completenessAt(RANKED, two, 3)).isEqualTo(1.0);
    }

    @Test
    void duplicatesCountOnce() {
        List<String> ranked = List.of("a", "a", "b");
        assertThat(recallAt(ranked, Map.of("b", 1), 2)).isEqualTo(1.0);
        assertThat(precisionAt(ranked, Map.of("a", 1), 2)).isEqualTo(0.5);
    }

    @Test
    void rejectsNonPositiveK() {
        assertThatThrownBy(() -> recallAt(RANKED, QRELS, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
