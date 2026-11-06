package io.github.rahulbsw.protoweft.evals.discovery.rerank;

import io.github.rahulbsw.protoweft.discovery.embeddings.WordPieceTokenizer;

import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Scores (query, passage) pairs with a BERT cross-encoder run in-process on ONNX Runtime, such as
 * the MS MARCO MiniLM rerankers ({@code cross-encoder/ms-marco-MiniLM-L6-v2}). Each pair is encoded
 * as {@code [CLS] query [SEP] passage [SEP]} with token types 0/1, truncated longest-first to
 * {@value #MAX_LENGTH} positions, and the model's single logit is the score (higher = more
 * relevant; not a probability).
 *
 * <p><b>Experimental</b>: used by the discovery evaluation's reranking experiment; no model ships
 * with Protoweft. The model directory (or classpath prefix) holds {@code model.onnx} and a BERT
 * {@code vocab.txt}; the tokenizer is {@link WordPieceTokenizer} (lowercasing BERT uncased rules).
 *
 * <p>Pairs are scored one per model run, never as a padded batch: dynamically quantized models
 * compute activation scales over the whole input tensor, so padding and batch neighbours change
 * the scores. {@link #score(String, List)} spreads the pairs over the common fork-join pool, so its
 * results equal single calls exactly. The model is loaded on first use, exactly once even under
 * concurrent first use; a failed load is rethrown as {@link IllegalStateException} and retried on
 * the next call. Thread-safe.
 */
public final class CrossEncoderReranker {

    /** Position embeddings of BERT-small/base models; {@code [CLS]} and both {@code [SEP]} included. */
    public static final int MAX_LENGTH = 512;

    private final String name;
    private final Supplier<Loaded> loader;
    private volatile Loaded loaded;

    private record Loaded(WordPieceTokenizer tokenizer, OnnxModel model) {
    }

    private CrossEncoderReranker(String name, Supplier<Loaded> loader) {
        this.name = Objects.requireNonNull(name, "name");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * A reranker over {@code model.onnx} and {@code vocab.txt} in {@code directory}. Loads nothing
     * yet; a missing file is reported on first use.
     */
    public static CrossEncoderReranker fromDirectory(Path directory) {
        return fromDirectory(directory, 0);
    }

    /**
     * As {@link #fromDirectory(Path)}, with ONNX Runtime's intra-op thread count per pair (0 = its
     * default, one per physical core). Pairs already run in parallel, so 1 avoids oversubscription.
     */
    public static CrossEncoderReranker fromDirectory(Path directory, int intraOpThreads) {
        Objects.requireNonNull(directory, "directory");
        if (intraOpThreads < 0) {
            throw new IllegalArgumentException("intraOpThreads must be >= 0: " + intraOpThreads);
        }
        Path dir = directory.toAbsolutePath().normalize();
        return new CrossEncoderReranker(dir.getFileName() == null ? dir.toString() : dir.getFileName().toString(),
                () -> load(dir.toString(), () -> read(dir.resolve("vocab.txt")), () -> read(dir.resolve("model.onnx")),
                        intraOpThreads));
    }

    /**
     * A reranker over the classpath resources {@code <prefix>model.onnx} and {@code <prefix>vocab.txt}
     * (prefix with a trailing slash, no leading one), read through {@code classLoader}.
     */
    public static CrossEncoderReranker fromClasspath(ClassLoader classLoader, String prefix) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(prefix, "prefix");
        return new CrossEncoderReranker(prefix,
                () -> load(prefix, () -> resource(classLoader, prefix + "vocab.txt"),
                        () -> resource(classLoader, prefix + "model.onnx"), 0));
    }

    /** The directory name or classpath prefix the model comes from. */
    public String name() {
        return name;
    }

    /** Whether the model has been loaded. */
    public boolean isLoaded() {
        return loaded != null;
    }

    /** Loads the model now (otherwise the first score does). */
    public CrossEncoderReranker load() {
        get();
        return this;
    }

    /**
     * The relevance logit of one pair.
     *
     * @throws IllegalArgumentException if the query or passage is blank
     */
    public float score(String query, String passage) {
        checked(query, "query");
        checked(passage, "passage");
        return run(get(), query, passage);
    }

    /**
     * The relevance logits of {@code query} against each passage, in order. Pairs run in parallel,
     * one per model run.
     *
     * @throws IllegalArgumentException if the query or any passage is blank (before any work)
     */
    public float[] score(String query, List<String> passages) {
        checked(query, "query");
        Objects.requireNonNull(passages, "passages");
        passages.forEach(p -> checked(p, "passage"));
        float[] scores = new float[passages.size()];
        if (passages.isEmpty()) {
            return scores;
        }
        Loaded l = get();
        if (passages.size() == 1) {
            scores[0] = run(l, query, passages.get(0));
            return scores;
        }
        IntStream.range(0, passages.size()).parallel().forEach(i -> scores[i] = run(l, query, passages.get(i)));
        return scores;
    }

    @Override
    public String toString() {
        return "CrossEncoderReranker[" + name + "]";
    }

    private static float run(Loaded l, String query, String passage) {
        WordPieceTokenizer.Encoding encoding = l.tokenizer().encodePair(query, passage, MAX_LENGTH);
        float[] logits = l.model().firstVector(encoding);
        return logits[0];
    }

    private Loaded get() {
        Loaded l = loaded;
        if (l == null) {
            synchronized (this) {
                l = loaded;
                if (l == null) {
                    try {
                        l = Objects.requireNonNull(loader.get(), "loader returned null");
                    } catch (RuntimeException | LinkageError e) {
                        throw new IllegalStateException("Could not load reranker model '" + name + "': " + e, e);
                    }
                    loaded = l;
                }
            }
        }
        return l;
    }

    private static Loaded load(String origin, Supplier<byte[]> vocab, Supplier<byte[]> model, int intraOpThreads) {
        WordPieceTokenizer tokenizer = WordPieceTokenizer.read(new java.io.ByteArrayInputStream(vocab.get()));
        OnnxModel onnx;
        try {
            onnx = OnnxModel.load(model.get(), intraOpThreads);
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX Runtime could not load " + origin + " model.onnx: "
                    + e.getMessage(), e);
        }
        long[] shape = onnx.firstOutputShape();
        if (!"logits".equals(onnx.firstOutputName()) || shape.length != 2 || shape[1] != 1) {
            try {
                onnx.close();
            } catch (OrtException ignored) {
                // best effort
            }
            throw new IllegalArgumentException("Not a single-logit cross-encoder: first output "
                    + onnx.firstOutputName() + " " + java.util.Arrays.toString(shape) + ", expected logits [batch, 1]");
        }
        return new Loaded(tokenizer, onnx);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static byte[] resource(ClassLoader classLoader, String resource) {
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    private static void checked(String text, String what) {
        Objects.requireNonNull(text, what);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Cannot score a blank " + what);
        }
    }
}
