package io.github.rahulbsw.protoweft.evals.discovery.rerank;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The MS MARCO MiniLM-L6 cross-encoder (quantized, Xenova/ms-marco-MiniLM-L-6-v2
 * {@code onnx/model_quantized.onnx}), downloaded by {@code evals/tool-discovery/scripts/fetch_reranker.py}.
 * Skipped when the model is absent; override the directory with {@code -Dprotoweft.reranker.dir=<dir>}
 * or {@code PROTOWEFT_RERANKER_DIR}.
 */
class CrossEncoderRerankerTest {

    static final String LONG_PASSAGE = String.join(" ",
            java.util.Collections.nCopies(60, "The quick brown fox jumps over the lazy dog near the riverbank."));

    static final String BERLIN = "How many people live in Berlin?";
    static final String FILE = "read the contents of a file";
    static final String WEATHER = "what's the weather in Paris tomorrow?";

    /** (query, passage) pairs and their reference logits. */
    static final List<String[]> PAIRS = List.of(
            new String[]{BERLIN, "Berlin has a population of 3,520,031 registered inhabitants in an area of 891.82 square kilometers."},
            new String[]{BERLIN, "New York City is famous for the Metropolitan Museum of Art."},
            new String[]{BERLIN, "Berlin is well known for its museums."},
            new String[]{FILE, "read file. Read the complete contents of a file from the file system. path: the file to read"},
            new String[]{FILE, "send email. Send an email message to a recipient. to subject body"},
            new String[]{WEATHER, "get forecast. Get the weather forecast for a city. city days"},
            new String[]{WEATHER, "playwright click. Click an element on the page. selector"},
            new String[]{"Café crème recipes — naïve façade?", "Recipes for café crème and other coffee drinks."},
            new String[]{"find a fox story", LONG_PASSAGE});

    /**
     * The same quantized file run by Python onnxruntime 1.23.2 (the version this module uses) with the
     * HuggingFace {@code tokenizers} 0.23.3 tokenizer of the same repo ({@code tokenizer.json}, truncation
     * longest_first 512), one unpadded pair per run. (onnxruntime 1.31.1 gives scores up to 0.31 apart on
     * the same file, e.g. 8.68985 for pair 0: quantized kernels differ between versions.)
     */
    static final double[] QUANTIZED_REFERENCE = {8.73189, -11.25048, -4.48801, 8.50185, -11.37437, -8.23865, -11.20401,
            -1.24530, -4.80129};

    /**
     * The unquantized export ({@code onnx/model.onnx}, same pipeline). It reproduces the model card's
     * sentence-transformers score for "Berlin is well known for its museums." (-4.320078).
     */
    static final double[] FP32_REFERENCE = {8.84585, -11.24556, -4.32008, 8.56983, -11.37499, -8.45207, -11.19024,
            -1.28954, -4.86747};

    private static CrossEncoderReranker reranker;

    @BeforeAll
    static void load() {
        Path dir = modelDir();
        assumeTrue(Files.isRegularFile(dir.resolve("model.onnx")) && Files.isRegularFile(dir.resolve("vocab.txt")),
                "reranker model not downloaded: " + dir);
        reranker = CrossEncoderReranker.fromDirectory(dir);
        long start = System.nanoTime();
        reranker.load();
        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        List<String> passages = IntStream.range(0, 50)
                .mapToObj(i -> "tool_" + i + ": reads the file at the given path and returns its lines. path encoding")
                .toList();
        passages.forEach(p -> reranker.score("read a file", p)); // warm up
        start = System.nanoTime();
        passages.forEach(p -> reranker.score("read a file", p));
        double single = (System.nanoTime() - start) / 1e6 / passages.size();
        start = System.nanoTime();
        reranker.score("read a file", passages);
        double parallel = (System.nanoTime() - start) / 1e6;
        System.out.printf("[timing] %s: load %d ms; %.2f ms/pair sequential; %.1f ms for 50 pairs in parallel"
                + " (%d cores)%n", reranker.name(), loadMillis, single, parallel, Runtime.getRuntime().availableProcessors());
    }

    static Path modelDir() {
        String configured = System.getProperty("protoweft.reranker.dir", System.getenv("PROTOWEFT_RERANKER_DIR"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path candidate = p.resolve("evals/tool-discovery/build/models/ms-marco-minilm-l6-v2");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return here.resolve("../evals/tool-discovery/build/models/ms-marco-minilm-l6-v2").normalize();
    }

    @Test
    void relevantPassagesScoreHigher() {
        float[] berlin = reranker.score(BERLIN, List.of(PAIRS.get(0)[1], PAIRS.get(1)[1], PAIRS.get(2)[1]));
        assertThat(berlin[0]).isGreaterThan(berlin[2]).isGreaterThan(berlin[1]);
        assertThat(reranker.score(FILE, PAIRS.get(3)[1])).isGreaterThan(reranker.score(FILE, PAIRS.get(4)[1]));
        assertThat(reranker.score(WEATHER, PAIRS.get(5)[1])).isGreaterThan(reranker.score(WEATHER, PAIRS.get(6)[1]));
    }

    @Test
    void matchesPythonOnnxRuntimeOnTheSameFile() {
        for (int i = 0; i < PAIRS.size(); i++) {
            float score = reranker.score(PAIRS.get(i)[0], PAIRS.get(i)[1]);
            assertThat((double) score).as("pair %d", i).isCloseTo(QUANTIZED_REFERENCE[i], within(1e-3));
            assertThat((double) score).as("pair %d vs fp32", i).isCloseTo(FP32_REFERENCE[i], within(0.3));
        }
    }

    @Test
    void parallelScoresEqualSingleScores() throws Exception {
        List<String> passages = new ArrayList<>();
        PAIRS.forEach(p -> passages.add(p[1]));
        float[] expected = new float[passages.size()];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = reranker.score(FILE, passages.get(i));
        }
        assertThat(reranker.score(FILE, passages)).containsExactly(expected);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<float[]>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> reranker.score(FILE, passages)));
            }
            for (Future<float[]> f : futures) {
                assertThat(f.get()).containsExactly(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void singleIntraOpThreadGivesTheSameScores() {
        CrossEncoderReranker oneThread = CrossEncoderReranker.fromDirectory(modelDir(), 1);
        assertThat(oneThread.isLoaded()).isFalse();
        for (String[] pair : PAIRS) {
            assertThat(oneThread.score(pair[0], pair[1])).isEqualTo(reranker.score(pair[0], pair[1]));
        }
        assertThatThrownBy(() -> CrossEncoderReranker.fromDirectory(modelDir(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingModelIsReportedOnFirstUseAndRetried(@org.junit.jupiter.api.io.TempDir Path empty) {
        CrossEncoderReranker missing = CrossEncoderReranker.fromDirectory(empty);
        assertThat(missing.isLoaded()).isFalse();
        assertThatThrownBy(() -> missing.score("q", "p")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not load reranker model");
        assertThatThrownBy(() -> missing.score("q", "p")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankInputBeforeAnyWork() {
        assertThatThrownBy(() -> reranker.score(" ", List.of("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reranker.score("q", List.of("x", ""))).isInstanceOf(IllegalArgumentException.class);
        assertThat(reranker.score("q", List.of())).isEmpty();
    }
}
