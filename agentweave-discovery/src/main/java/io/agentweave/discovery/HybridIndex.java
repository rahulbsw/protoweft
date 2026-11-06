package io.agentweave.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

/**
 * In-memory index: field-weighted BM25, plus cosine similarity when an {@link Embedder} is
 * given, fused per {@link Fusion} (default: a convex blend of normalized scores). Sized for
 * thousands of capabilities.
 */
public final class HybridIndex implements CapabilityIndex {

    /** BM25 parameters (Robertson/Zaragoza defaults, as in Lucene). */
    static final double K1 = 1.2;
    static final double B = 0.75;
    /** RRF constant from Cormack, Clarke and Büttcher (SIGIR 2009). */
    static final int RRF_K = 60;

    /**
     * How keyword and vector rankings combine in hybrid mode.
     *
     * @param kind          {@code RRF}: weighted Reciprocal Rank Fusion, {@code w / (k + rank)} per list;
     *                      {@code CONVEX}: {@code alpha * cos' + (1 - alpha) * bm25'} over per-query
     *                      min-max-normalized scores (0 for a list a capability isn't in)
     * @param rrfK          the RRF constant
     * @param keywordWeight RRF weight of the keyword list
     * @param vectorWeight  RRF weight of the vector list
     * @param alpha         CONVEX weight of the vector score, in [0, 1]
     */
    public record Fusion(Kind kind, int rrfK, double keywordWeight, double vectorWeight, double alpha) {

        public enum Kind { RRF, CONVEX }

        /** Convex fusion with alpha = 0.8, chosen by measurement (see the module spec). */
        public static final Fusion DEFAULT = convex(0.8);

        public Fusion {
            java.util.Objects.requireNonNull(kind, "kind");
            if (rrfK < 0 || keywordWeight < 0 || vectorWeight < 0 || alpha < 0 || alpha > 1) {
                throw new IllegalArgumentException("fusion parameters out of range: " + this);
            }
        }

        public static Fusion rrf(int k, double keywordWeight, double vectorWeight) {
            return new Fusion(Kind.RRF, k, keywordWeight, vectorWeight, 0.5);
        }

        public static Fusion convex(double alpha) {
            return new Fusion(Kind.CONVEX, RRF_K, 1, 1, alpha);
        }
    }

    private final Embedder embedder;
    private final Fusion fusion;
    private final boolean embedDescription;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Map<String, Double>> termFrequencies = new HashMap<>();
    private final Map<String, Double> lengths = new HashMap<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final Map<String, float[]> vectors = new HashMap<>();
    private double totalLength;

    /** A keyword-only index. */
    public HybridIndex() {
        this(null);
    }

    /** A hybrid index; {@code embedder} may be {@code null} for keyword-only. */
    public HybridIndex(Embedder embedder) {
        this(embedder, Fusion.DEFAULT, false);
    }

    /**
     * A hybrid index with explicit tuning.
     *
     * @param embedder         {@code null} for keyword-only (fusion then doesn't apply)
     * @param fusion           how the keyword and vector rankings combine
     * @param embedDescription embed the full description too, not only name, title, summary,
     *                         tags and parameters
     */
    public HybridIndex(Embedder embedder, Fusion fusion, boolean embedDescription) {
        this.embedder = embedder;
        this.fusion = java.util.Objects.requireNonNull(fusion, "fusion");
        this.embedDescription = embedDescription;
    }

    /** The fusion in use. */
    public Fusion fusion() {
        return fusion;
    }

    @Override
    public boolean semantic() {
        return embedder != null;
    }

    @Override
    public void upsert(Collection<Document> documents) {
        List<float[]> embedded = null;
        if (embedder != null && !documents.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (Document d : documents) {
                texts.add(vectorText(d, embedDescription));
            }
            embedded = embedder.embedDocuments(texts);
        }
        lock.writeLock().lock();
        try {
            int i = 0;
            for (Document d : documents) {
                removeLocked(d.id());
                Map<String, Double> tf = weightedTerms(d);
                termFrequencies.put(d.id(), tf);
                double length = tf.values().stream().mapToDouble(Double::doubleValue).sum();
                lengths.put(d.id(), length);
                totalLength += length;
                tf.keySet().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
                if (embedded != null) {
                    vectors.put(d.id(), normalize(embedded.get(i)));
                }
                i++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(Collection<String> ids) {
        lock.writeLock().lock();
        try {
            ids.forEach(this::removeLocked);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Result search(String query, Predicate<String> candidates, int limit, double minCosine) {
        List<String> queryTerms = new ArrayList<>(new LinkedHashSet<>(Text.tokens(query)));
        float[] queryVector = embedder != null && !query.isBlank() ? normalize(embedder.embedQuery(query)) : null;
        lock.readLock().lock();
        try {
            int n = termFrequencies.size();
            double avgLength = n == 0 ? 0 : totalLength / n;
            Map<String, double[]> keyword = new HashMap<>(); // id -> {bm25, coverage}
            Map<String, Double> cosine = new HashMap<>();
            for (Map.Entry<String, Map<String, Double>> doc : termFrequencies.entrySet()) {
                String id = doc.getKey();
                if (!candidates.test(id)) {
                    continue;
                }
                double score = 0;
                int matched = 0;
                for (String term : queryTerms) {
                    Double tf = doc.getValue().get(term);
                    if (tf == null) {
                        continue;
                    }
                    matched++;
                    int df = documentFrequency.getOrDefault(term, 0);
                    double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                    double norm = K1 * (1 - B + B * lengths.get(id) / avgLength);
                    score += idf * tf * (K1 + 1) / (tf + norm);
                }
                if (matched > 0) {
                    keyword.put(id, new double[]{score, (double) matched / queryTerms.size()});
                }
                if (queryVector != null) {
                    float[] v = vectors.get(id);
                    if (v != null) {
                        cosine.put(id, dot(queryVector, v));
                    }
                }
            }
            return fuse(keyword, cosine, limit, minCosine, queryVector != null, fusion);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static Result fuse(Map<String, double[]> keyword, Map<String, Double> cosine, int limit,
                               double minCosine, boolean semantic, Fusion fusion) {
        List<String> byKeyword = new ArrayList<>(keyword.keySet());
        byKeyword.sort(Comparator.comparingDouble((String id) -> -keyword.get(id)[0]).thenComparing(id -> id));
        List<String> byVector = new ArrayList<>();
        for (Map.Entry<String, Double> e : cosine.entrySet()) {
            if (e.getValue() >= minCosine || keyword.containsKey(e.getKey())) {
                byVector.add(e.getKey());
            }
        }
        byVector.sort(Comparator.comparingDouble((String id) -> -cosine.get(id)).thenComparing(id -> id));

        Map<String, Integer> keywordRank = ranks(byKeyword);
        Map<String, Integer> vectorRank = ranks(byVector);
        Set<String> ids = new HashSet<>(keywordRank.keySet());
        ids.addAll(vectorRank.keySet());
        double[] kwRange = range(byKeyword.stream().mapToDouble(id -> keyword.get(id)[0]));
        double[] cosRange = range(byVector.stream().mapToDouble(cosine::get));
        List<Hit> hits = new ArrayList<>();
        for (String id : ids) {
            int kr = keywordRank.getOrDefault(id, 0);
            int vr = vectorRank.getOrDefault(id, 0);
            double score;
            if (!semantic) {
                score = keyword.get(id)[0];
            } else if (fusion.kind() == Fusion.Kind.RRF) {
                score = fusion.keywordWeight() * rrf(kr, fusion.rrfK()) + fusion.vectorWeight() * rrf(vr, fusion.rrfK());
            } else {
                double kwNorm = kr == 0 ? 0 : normalized(keyword.get(id)[0], kwRange);
                double cosNorm = vr == 0 ? 0 : normalized(cosine.get(id), cosRange);
                score = fusion.alpha() * cosNorm + (1 - fusion.alpha()) * kwNorm;
            }
            double[] kw = keyword.getOrDefault(id, new double[]{0, 0});
            hits.add(new Hit(id, score, kr, kw[0], kw[1], vr, cosine.getOrDefault(id, Double.NaN)));
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> -h.score()).thenComparing(Hit::id));
        return new Result(hits.subList(0, Math.min(limit, hits.size())), semantic);
    }

    static double rrf(int rank) {
        return rrf(rank, RRF_K);
    }

    static double rrf(int rank, int k) {
        return rank == 0 ? 0 : 1.0 / (k + rank);
    }

    private static double[] range(java.util.stream.DoubleStream values) {
        java.util.DoubleSummaryStatistics s = values.summaryStatistics();
        return s.getCount() == 0 ? new double[]{0, 0} : new double[]{s.getMin(), s.getMax()};
    }

    /** Min-max normalization; a list whose scores are all equal maps to 1. */
    private static double normalized(double value, double[] range) {
        return range[1] > range[0] ? (value - range[0]) / (range[1] - range[0]) : 1;
    }

    private static Map<String, Integer> ranks(List<String> ordered) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            ranks.put(ordered.get(i), i + 1);
        }
        return ranks;
    }

    private void removeLocked(String id) {
        Map<String, Double> old = termFrequencies.remove(id);
        if (old != null) {
            totalLength -= lengths.remove(id);
            old.keySet().forEach(t -> documentFrequency.computeIfPresent(t, (k, c) -> c == 1 ? null : c - 1));
        }
        vectors.remove(id);
    }

    private static Map<String, Double> weightedTerms(Document d) {
        Map<String, Double> tf = new HashMap<>();
        d.fields().forEach((field, text) -> {
            for (String token : Text.tokens(text)) {
                tf.merge(token, (double) field.weight(), Double::sum);
            }
        });
        return tf;
    }

    private static final List<Field> VECTOR_FIELDS = List.of(Field.NAME, Field.TITLE, Field.SUMMARY, Field.TAGS,
            Field.PARAMETERS);
    private static final List<Field> VECTOR_FIELDS_WITH_DESCRIPTION = List.of(Field.NAME, Field.TITLE,
            Field.DESCRIPTION, Field.TAGS, Field.PARAMETERS);

    private static String vectorText(Document d, boolean withDescription) {
        StringBuilder out = new StringBuilder();
        for (Field f : withDescription ? VECTOR_FIELDS_WITH_DESCRIPTION : VECTOR_FIELDS) {
            String text = d.fields().get(f);
            if (text != null && !text.isBlank()) {
                out.append(out.isEmpty() ? "" : ". ").append(text);
            }
        }
        return out.toString();
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = norm == 0 ? 0 : (float) (v[i] / norm);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
