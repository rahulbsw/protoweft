package io.github.rahulbsw.protoweft.evals.discovery.metrics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Per-query ranking metrics with trec_eval / pytrec_eval semantics, as used by ToolRet
 * ({@code toolret/eval.py}: {@code P.k}, {@code recall.k}, {@code ndcg_cut.k}, and
 * "Comprehensiveness@k" = {@code recall_k == 1}).
 *
 * <p>Inputs: {@code ranked} is the retriever's output, best first (duplicates are ignored after
 * their first occurrence); {@code qrels} maps document id to graded relevance, and a document is
 * relevant when its grade is {@code >= 1} (trec_eval's default relevance level).
 * <ul>
 *   <li><b>Precision@k</b> = |relevant ∩ top-k| / k. The denominator is k even if fewer than k
 *       documents were retrieved (trec_eval {@code P}).</li>
 *   <li><b>Recall@k</b> = |relevant ∩ top-k| / |relevant|.</li>
 *   <li><b>nDCG@k</b> = DCG@k / IDCG@k with DCG = Σ<sub>i=1..k</sub> gain<sub>i</sub> /
 *       log<sub>2</sub>(i+1), gain = the relevance grade (trec_eval's linear gain, not
 *       2<sup>g</sup>−1), and IDCG computed from all positive qrels sorted by grade.</li>
 *   <li><b>MRR</b> (trec_eval {@code recip_rank}) = 1 / rank of the first relevant document in
 *       the whole retrieved list, 0 if none was retrieved. Its depth is the retrieval depth.</li>
 *   <li><b>Completeness@k</b> = 1 if every relevant document is in the top k, else 0.</li>
 * </ul>
 * A query without relevant documents scores 0 on every metric; loaders never produce one.
 * Note trec_eval re-sorts runs by score (ties by document id); here the list order is the rank.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    public static double precisionAt(List<String> ranked, Map<String, Integer> qrels, int k) {
        requirePositive(k);
        return (double) hits(ranked, qrels, k) / k;
    }

    public static double recallAt(List<String> ranked, Map<String, Integer> qrels, int k) {
        requirePositive(k);
        long relevant = relevantCount(qrels);
        return relevant == 0 ? 0.0 : (double) hits(ranked, qrels, k) / relevant;
    }

    public static double ndcgAt(List<String> ranked, Map<String, Integer> qrels, int k) {
        requirePositive(k);
        List<String> top = topK(ranked, k);
        double dcg = 0;
        for (int i = 0; i < top.size(); i++) {
            int grade = qrels.getOrDefault(top.get(i), 0);
            if (grade > 0) {
                dcg += grade / log2(i + 2);
            }
        }
        List<Integer> ideal = new ArrayList<>(qrels.values().stream().filter(g -> g > 0).toList());
        ideal.sort((a, b) -> Integer.compare(b, a));
        double idcg = 0;
        for (int i = 0; i < Math.min(k, ideal.size()); i++) {
            idcg += ideal.get(i) / log2(i + 2);
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    public static double reciprocalRank(List<String> ranked, Map<String, Integer> qrels) {
        List<String> all = topK(ranked, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) {
            if (qrels.getOrDefault(all.get(i), 0) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double completenessAt(List<String> ranked, Map<String, Integer> qrels, int k) {
        requirePositive(k);
        long relevant = relevantCount(qrels);
        return relevant > 0 && hits(ranked, qrels, k) == relevant ? 1.0 : 0.0;
    }

    /** The first k distinct ids of the ranking. */
    public static List<String> topK(List<String> ranked, int k) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String id : ranked) {
            if (seen.size() >= k) {
                break;
            }
            seen.add(id);
        }
        return List.copyOf(seen);
    }

    private static long hits(List<String> ranked, Map<String, Integer> qrels, int k) {
        return topK(ranked, k).stream().filter(id -> qrels.getOrDefault(id, 0) > 0).count();
    }

    private static long relevantCount(Map<String, Integer> qrels) {
        return qrels.values().stream().filter(g -> g > 0).count();
    }

    private static double log2(int x) {
        return Math.log(x) / Math.log(2);
    }

    private static void requirePositive(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
    }
}
