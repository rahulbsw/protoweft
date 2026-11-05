package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.agentweave.discovery.Embedder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The real in-process models (ONNX Runtime, CPU). */
class OfflineModelsTest {

    static Stream<Embedder> models() {
        return Stream.of(OfflineEmbedders.bgeSmallEnV15(), OfflineEmbedders.allMiniLmL6V2());
    }

    @BeforeAll
    static void loadAndMeasure() {
        // Records first-load and steady-state latency in the test report's system-out.
        models().forEach(embedder -> {
            long start = System.nanoTime();
            embedder.embedDocument("warm up");
            long firstMillis = (System.nanoTime() - start) / 1_000_000;

            List<String> docs = IntStream.range(0, 50)
                    .mapToObj(i -> "tool_" + i + ": reads the file at the given path and returns its lines")
                    .toList();
            start = System.nanoTime();
            docs.forEach(embedder::embedDocument);
            double sequentialMillis = (System.nanoTime() - start) / 1e6 / docs.size();
            start = System.nanoTime();
            embedder.embedDocuments(docs);
            double batchedMillis = (System.nanoTime() - start) / 1e6 / docs.size();

            System.out.printf("[timing] %s: first embedding (incl. load) %d ms; %.2f ms/embedding sequential;"
                            + " %.2f ms/embedding batched (%d cores)%n",
                    embedder.name(), firstMillis, sequentialMillis, batchedMillis,
                    Runtime.getRuntime().availableProcessors());
        });
    }

    @ParameterizedTest
    @MethodSource("models")
    void producesVectorsOfTheDeclaredDimension(Embedder embedder) {
        assertThat(embedder.dimension()).isEqualTo(384);
        assertThat(embedder.embedDocument("fs.read: read a file")).hasSize(384);
        assertThat(embedder.embedQuery("read a file")).hasSize(384);
    }

    @ParameterizedTest
    @MethodSource("models")
    void sameInputGivesTheSameVector(Embedder embedder) {
        float[] first = embedder.embedDocument("git.commit: record changes to the repository");
        float[] second = embedder.embedDocument("git.commit: record changes to the repository");

        assertThat(second).containsExactly(first);
        assertThat(embedder.embedQuery("commit my changes")).containsExactly(embedder.embedQuery("commit my changes"));
    }

    @ParameterizedTest
    @MethodSource("models")
    void paraphraseRanksAboveUnrelatedText(Embedder embedder) {
        assertCloser(embedder, "list files in a directory", "List the contents of a folder", "Get the current weather");
        assertCloser(embedder, "what's the temperature outside", "Get the current weather", "List the contents of a folder");
        assertCloser(embedder, "send an email to my boss", "Compose and send an email message", "Delete a database table");
    }

    @ParameterizedTest
    @MethodSource("models")
    void batchedEmbeddingsMatchSingleEmbeddings(Embedder embedder) {
        List<String> docs = List.of("Get the current weather", "List the contents of a folder", "Translate text");

        List<float[]> batched = embedder.embedDocuments(docs);

        assertThat(batched).hasSize(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            assertThat(batched.get(i)).containsExactly(embedder.embedDocument(docs.get(i)), within(1e-6f));
        }
    }

    @ParameterizedTest
    @MethodSource("models")
    void concurrentUseGivesTheSameVectorsAsSequentialUse(Embedder embedder) throws Exception {
        List<String> texts = IntStream.range(0, 24).mapToObj(i -> "capability number " + i + " does task " + i).toList();
        List<float[]> expected = texts.stream().map(embedder::embedDocument).toList();

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
                        out.add(embedder.embedDocument(text));
                    }
                    out.addAll(embedder.embedDocuments(texts));
                    return out;
                }));
            }
            start.countDown();
            for (Future<List<float[]>> future : futures) {
                List<float[]> got = future.get(120, TimeUnit.SECONDS);
                for (int i = 0; i < got.size(); i++) {
                    assertThat(got.get(i)).containsExactly(expected.get(i % texts.size()), within(1e-6f));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bgeQueryVectorDiffersFromDocumentVectorForTheSameText() {
        Embedder bge = OfflineEmbedders.bgeSmallEnV15();
        String text = "list files in a directory";

        assertThat(cosine(bge.embedQuery(text), bge.embedDocument(text))).isLessThan(0.9999);
    }

    @Test
    void miniLmQueryAndDocumentVectorsAreTheSameForTheSameText() {
        Embedder miniLm = OfflineEmbedders.allMiniLmL6V2();
        String text = "list files in a directory";

        assertThat(miniLm.embedQuery(text)).containsExactly(miniLm.embedDocument(text));
    }

    private static void assertCloser(Embedder embedder, String query, String paraphrase, String unrelated) {
        float[] q = embedder.embedQuery(query);
        double near = cosine(q, embedder.embedDocument(paraphrase));
        double far = cosine(q, embedder.embedDocument(unrelated));
        assertThat(near)
                .as("%s: cos(%s, %s)=%.3f vs cos(.., %s)=%.3f", embedder.name(), query, paraphrase, near, unrelated, far)
                .isGreaterThan(far);
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
