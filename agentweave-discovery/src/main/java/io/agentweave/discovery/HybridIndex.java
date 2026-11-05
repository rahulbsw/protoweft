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
 * given, fused with Reciprocal Rank Fusion. Sized for thousands of capabilities.
 */
public final class HybridIndex implements CapabilityIndex {

    /** BM25 parameters (Robertson/Zaragoza defaults, as in Lucene). */
    static final double K1 = 1.2;
    static final double B = 0.75;
    /** RRF constant from Cormack, Clarke and Büttcher (SIGIR 2009). */
    static final int RRF_K = 60;

    private final Embedder embedder;
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
        this.embedder = embedder;
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
                texts.add(vectorText(d));
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
            return fuse(keyword, cosine, limit, minCosine, queryVector != null);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static Result fuse(Map<String, double[]> keyword, Map<String, Double> cosine, int limit,
                               double minCosine, boolean semantic) {
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
        List<Hit> hits = new ArrayList<>();
        for (String id : ids) {
            int kr = keywordRank.getOrDefault(id, 0);
            int vr = vectorRank.getOrDefault(id, 0);
            double score = semantic ? rrf(kr) + rrf(vr) : keyword.get(id)[0];
            double[] kw = keyword.getOrDefault(id, new double[]{0, 0});
            hits.add(new Hit(id, score, kr, kw[0], kw[1], vr, cosine.getOrDefault(id, Double.NaN)));
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> -h.score()).thenComparing(Hit::id));
        return new Result(hits.subList(0, Math.min(limit, hits.size())), semantic);
    }

    static double rrf(int rank) {
        return rank == 0 ? 0 : 1.0 / (RRF_K + rank);
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

    private static String vectorText(Document d) {
        StringBuilder out = new StringBuilder();
        for (Field f : List.of(Field.NAME, Field.TITLE, Field.SUMMARY, Field.TAGS, Field.PARAMETERS)) {
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
