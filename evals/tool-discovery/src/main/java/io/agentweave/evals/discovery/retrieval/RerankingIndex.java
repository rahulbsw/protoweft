package io.agentweave.evals.discovery.retrieval;

import io.agentweave.discovery.CapabilityIndex;
import io.agentweave.discovery.HybridIndex;
import io.agentweave.evals.discovery.rerank.CrossEncoderReranker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Experimental cross-encoder reranking for the {@code catalog-hybrid:rerank=…} retrievers: a
 * {@link HybridIndex} ranks as usual, its top {@code depth} hits are scored by a
 * {@link CrossEncoderReranker} against the query, and re-sorted (ties keep the base order). Hits
 * below the window follow in base order. Not a product mode.
 *
 * <p>Final score per window hit: with {@code mix} {@code NaN} (pure reranking), the sigmoid of the
 * reranker logit; with {@code mix = w}, {@code w * z(logit) + (1 - w) * z(base score)}, where
 * {@code z} is min-max normalization within the window (1 when all values are equal). Hits below
 * the window get score 0. The hits keep the base signals (keyword rank and score, coverage, vector
 * rank, cosine), so {@link io.agentweave.discovery.Confidence} still has its inputs, though its
 * margin is then computed over these scores and no preset is calibrated for them.
 *
 * <p>Reranker passages ({@link Text}): {@code CARD} = name, title, summary, tags, parameter names
 * and descriptions (the fields {@code HybridIndex} embeds); {@code DESCRIPTION} = the same with the
 * full description instead of the summary. Fields are joined by {@code ". "}; the name is spelled
 * out ({@code get_current_weather}/{@code getCurrentWeather} → {@code get current weather}),
 * because WordPiece would otherwise split camelCase badly.
 *
 * <p>Time spent reranking is accumulated per thread and taken with {@link #takeRerankStats()}.
 */
final class RerankingIndex implements CapabilityIndex {

    /** Default number of base hits reranked. */
    static final int DEFAULT_DEPTH = 50;

    /** Which document text the reranker reads. */
    enum Text { CARD, DESCRIPTION }

    /**
     * Reranking work since the last {@link #takeRerankStats()} on this thread.
     *
     * @param millis wall time of tokenizing and scoring
     * @param pairs  (query, passage) pairs scored
     * @param calls  searches that reranked
     */
    record RerankStats(double millis, int pairs, int calls) {
        static final RerankStats NONE = new RerankStats(0, 0, 0);

        RerankStats plus(double ms, int n) {
            return new RerankStats(millis + ms, pairs + n, calls + 1);
        }
    }

    /** Scores passages against a query, higher = more relevant (a {@link CrossEncoderReranker}). */
    @FunctionalInterface
    interface Scorer {
        float[] score(String query, List<String> passages);
    }

    private static final ThreadLocal<RerankStats> STATS = ThreadLocal.withInitial(() -> RerankStats.NONE);

    private static final List<Field> CARD_FIELDS = List.of(Field.NAME, Field.TITLE, Field.SUMMARY, Field.TAGS,
            Field.PARAMETERS);
    private static final List<Field> DESCRIPTION_FIELDS = List.of(Field.NAME, Field.TITLE, Field.DESCRIPTION,
            Field.TAGS, Field.PARAMETERS);

    private final CapabilityIndex base;
    private final Scorer reranker;
    private final int depth;
    private final Text text;
    private final double mix;
    private final Map<String, String> passages = new ConcurrentHashMap<>();

    /**
     * @param depth how many base hits to rerank (at least 1)
     * @param mix   {@code NaN} for pure reranking, else the reranker weight in [0, 1]
     */
    RerankingIndex(CapabilityIndex base, CrossEncoderReranker reranker, int depth, Text text, double mix) {
        this(base, reranker::score, depth, text, mix);
    }

    RerankingIndex(CapabilityIndex base, Scorer reranker, int depth, Text text, double mix) {
        this.base = Objects.requireNonNull(base, "base");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        if (depth < 1) {
            throw new IllegalArgumentException("rerank depth must be at least 1: " + depth);
        }
        if (!Double.isNaN(mix) && (mix < 0 || mix > 1)) {
            throw new IllegalArgumentException("rerank mix must be in [0, 1]: " + mix);
        }
        this.depth = depth;
        this.text = Objects.requireNonNull(text, "text");
        this.mix = mix;
    }

    /** Returns and resets this thread's reranking work. */
    static RerankStats takeRerankStats() {
        RerankStats s = STATS.get();
        STATS.set(RerankStats.NONE);
        return s;
    }

    @Override
    public void upsert(Collection<Document> documents) {
        base.upsert(documents);
        for (Document d : documents) {
            passages.put(d.id(), passage(d, text));
        }
    }

    @Override
    public void remove(Collection<String> ids) {
        base.remove(ids);
        ids.forEach(passages::remove);
    }

    @Override
    public Result search(String query, Predicate<String> candidates, int limit, double minCosine) {
        Result r = base.search(query, candidates, Math.max(limit, depth), minCosine);
        List<Hit> hits = r.hits();
        if (hits.isEmpty() || query.isBlank() || limit <= 0) {
            return new Result(hits.subList(0, Math.min(Math.max(limit, 0), hits.size())), r.semantic());
        }
        int window = Math.min(depth, hits.size());
        List<String> texts = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            String p = passages.get(hits.get(i).id());
            texts.add(p == null || p.isBlank() ? hits.get(i).id() : p);
        }
        long start = System.nanoTime();
        float[] logits = reranker.score(query, texts);
        STATS.set(STATS.get().plus((System.nanoTime() - start) / 1e6, window));
        if (logits.length != window) {
            throw new IllegalStateException("reranker returned " + logits.length + " scores for " + window + " passages");
        }

        double[] finals = finalScores(logits, hits.subList(0, window), mix);
        List<Integer> order = IntStream.range(0, window).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> -finals[i]).thenComparingInt(i -> i))
                .toList();
        List<Hit> out = new ArrayList<>(Math.min(limit, hits.size()));
        for (int i : order) {
            if (out.size() >= limit) {
                break;
            }
            out.add(withScore(hits.get(i), finals[i]));
        }
        for (int i = window; i < hits.size() && out.size() < limit; i++) {
            out.add(withScore(hits.get(i), 0));
        }
        return new Result(out, r.semantic());
    }

    @Override
    public boolean semantic() {
        return base.semantic();
    }

    /** Final scores for the window (see the class comment). */
    static double[] finalScores(float[] logits, List<Hit> window, double mix) {
        double[] out = new double[logits.length];
        if (Double.isNaN(mix)) {
            for (int i = 0; i < logits.length; i++) {
                out[i] = 1 / (1 + Math.exp(-logits[i]));
            }
            return out;
        }
        double[] rr = new double[logits.length];
        double[] bs = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            rr[i] = logits[i];
            bs[i] = window.get(i).score();
        }
        double[] zr = minMax(rr);
        double[] zb = minMax(bs);
        for (int i = 0; i < out.length; i++) {
            out[i] = mix * zr[i] + (1 - mix) * zb[i];
        }
        return out;
    }

    private static double[] minMax(double[] v) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = max > min ? (v[i] - min) / (max - min) : 1;
        }
        return out;
    }

    private static Hit withScore(Hit h, double score) {
        return new Hit(h.id(), score, h.keywordRank(), h.keywordScore(), h.coverage(), h.vectorRank(), h.cosine());
    }

    /** The reranker passage of a document. */
    static String passage(Document d, Text text) {
        StringBuilder out = new StringBuilder();
        for (Field f : text == Text.CARD ? CARD_FIELDS : DESCRIPTION_FIELDS) {
            String value = d.fields().get(f);
            if (value == null || value.isBlank()) {
                continue;
            }
            value = f == Field.NAME ? spellName(value) : value.strip();
            out.append(out.isEmpty() ? "" : ". ").append(value);
        }
        return out.toString();
    }

    /** {@code get_current_weather}, {@code getCurrentWeather}, {@code HTTPServer-v2} → spaced words. */
    static String spellName(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("[_\\-.:/]+", " ");
        return spaced.strip().replaceAll("\\s+", " ");
    }
}
