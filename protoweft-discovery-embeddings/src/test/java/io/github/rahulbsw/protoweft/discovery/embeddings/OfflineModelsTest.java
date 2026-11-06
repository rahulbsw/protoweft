package io.github.rahulbsw.protoweft.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rahulbsw.protoweft.discovery.Embedder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The real in-process bge model (ONNX Runtime, CPU). */
class OfflineModelsTest {

    private static final Embedder BGE = OfflineEmbedders.bgeSmallEnV15();

    @BeforeAll
    static void loadAndMeasure() {
        // Records first-load and steady-state latency in the test report's system-out.
        long start = System.nanoTime();
        BGE.embedDocument("warm up");
        long firstMillis = (System.nanoTime() - start) / 1_000_000;

        List<String> docs = IntStream.range(0, 100)
                .mapToObj(i -> "tool_" + i + ": reads the file at the given path and returns its lines")
                .toList();
        start = System.nanoTime();
        docs.forEach(BGE::embedDocument);
        double sequentialMillis = (System.nanoTime() - start) / 1e6 / docs.size();
        start = System.nanoTime();
        BGE.embedDocuments(docs);
        double batchedMillis = (System.nanoTime() - start) / 1e6 / docs.size();

        System.out.printf("[timing] %s: first embedding (incl. load) %d ms; %.2f ms/embedding sequential;"
                        + " %.2f ms/embedding in a batch of 100 (%d cores)%n",
                BGE.name(), firstMillis, sequentialMillis, batchedMillis, Runtime.getRuntime().availableProcessors());
    }

    @Test
    void producesNormalizedVectorsOfTheDeclaredDimension() {
        float[] vector = BGE.embedDocument("fs.read: read a file");

        assertThat(BGE.dimension()).isEqualTo(384);
        assertThat(vector).hasSize(384);
        assertThat(BGE.embedQuery("read a file")).hasSize(384);
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void sameInputGivesTheSameVector() {
        assertThat(BGE.embedDocument("git.commit: record changes to the repository"))
                .containsExactly(BGE.embedDocument("git.commit: record changes to the repository"));
        assertThat(BGE.embedQuery("commit my changes")).containsExactly(BGE.embedQuery("commit my changes"));
    }

    @Test
    void paraphraseRanksAboveUnrelatedText() {
        assertCloser("list files in a directory", "List the contents of a folder", "Get the current weather");
        assertCloser("what's the temperature outside", "Get the current weather", "List the contents of a folder");
        assertCloser("send an email to my boss", "Compose and send an email message", "Delete a database table");
    }

    @Test
    void batchedEmbeddingsEqualSingleEmbeddings() {
        List<String> docs = new ArrayList<>(List.of("Get the current weather", "List the contents of a folder",
                "Translate text", "x", "word ".repeat(700)));
        docs.addAll(ParityCorpus.tricky());

        List<float[]> batched = BGE.embedDocuments(docs);

        assertThat(batched).hasSize(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            assertThat(batched.get(i)).as(docs.get(i)).containsExactly(BGE.embedDocument(docs.get(i)));
        }
        assertThat(BGE.embedDocuments(List.of())).isEmpty();
    }

    @Test
    void concurrentUseGivesTheSameVectorsAsSequentialUse() throws Exception {
        List<String> texts = IntStream.range(0, 24).mapToObj(i -> "capability number " + i + " does task " + i).toList();
        List<float[]> expected = texts.stream().map(BGE::embedDocument).toList();

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<float[]>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<float[]> out = new ArrayList<>();
                    for (String text : texts) {
                        out.add(BGE.embedDocument(text));
                    }
                    out.addAll(BGE.embedDocuments(texts));
                    return out;
                }));
            }
            start.countDown();
            for (Future<List<float[]>> future : futures) {
                List<float[]> got = future.get(120, TimeUnit.SECONDS);
                for (int i = 0; i < got.size(); i++) {
                    assertThat(got.get(i)).containsExactly(expected.get(i % texts.size()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queryVectorDiffersFromDocumentVectorForTheSameText() {
        String text = "list files in a directory";

        assertThat(References.cosine(BGE.embedQuery(text), BGE.embedDocument(text))).isLessThan(0.9999);
    }

    @Test
    void textWithoutTokensIsRejected() {
        assertThatThrownBy(() -> BGE.embedDocument("\u0000\u0001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no tokens");
    }

    private static void assertCloser(String query, String paraphrase, String unrelated) {
        float[] q = BGE.embedQuery(query);
        double near = References.cosine(q, BGE.embedDocument(paraphrase));
        double far = References.cosine(q, BGE.embedDocument(unrelated));
        assertThat(near)
                .as("cos(%s, %s)=%.3f vs cos(.., %s)=%.3f", query, paraphrase, near, unrelated, far)
                .isGreaterThan(far);
    }
}
