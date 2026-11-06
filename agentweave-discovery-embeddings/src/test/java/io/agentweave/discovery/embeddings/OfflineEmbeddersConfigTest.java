package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The offline configuration over a fake encoder: prefixes, input checks, lazy once-only loading. */
class OfflineEmbeddersConfigTest {

    /** Records every text and returns a vector derived from it. */
    static final class RecordingEncoder implements TextEncoder {
        final List<String> inputs = new CopyOnWriteArrayList<>();
        final int dimension;

        RecordingEncoder(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) {
            inputs.add(text);
            float[] v = new float[dimension];
            v[0] = text.length();
            return v;
        }
    }

    @Test
    void bgePrefixesQueriesButNotDocuments() {
        RecordingEncoder encoder = new RecordingEncoder(OfflineEmbedders.DIMENSION);
        OfflineEmbedder bge = OfflineEmbedders.bge(() -> encoder);

        bge.embedQuery("list files in a directory");
        bge.embedDocument("fs.list_dir: List the contents of a folder");
        bge.embedDocuments(List.of("weather.current: Get the current weather"));

        assertThat(encoder.inputs).containsExactly(
                "Represent this sentence for searching relevant passages: list files in a directory",
                "fs.list_dir: List the contents of a folder",
                "weather.current: Get the current weather");
        assertThat(bge.name()).isEqualTo("bge-small-en-v1.5-q").isEqualTo(OfflineEmbedders.BGE_SMALL_EN_V15_NAME);
        assertThat(bge.queryPrefix()).isEqualTo(OfflineEmbedders.BGE_QUERY_PREFIX);
    }

    @Test
    void embedDocumentsKeepsOrderAndRejectsBadInputBeforeEmbedding() {
        RecordingEncoder encoder = new RecordingEncoder(4);
        OfflineEmbedder embedder = new OfflineEmbedder("fake", 4, "", () -> encoder);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            texts.add("t".repeat(i + 1));
        }

        List<float[]> vectors = embedder.embedDocuments(texts);

        for (int i = 0; i < texts.size(); i++) {
            assertThat(vectors.get(i)[0]).isEqualTo(i + 1);
        }
        encoder.inputs.clear();
        assertThatThrownBy(() -> embedder.embedDocuments(List.of("ok", " "))).isInstanceOf(IllegalArgumentException.class);
        assertThat(encoder.inputs).isEmpty();
        assertThat(embedder.embedDocuments(List.of())).isEmpty();
    }

    @Test
    void rejectsBlankAndNullTextAndWrongDimensions() {
        OfflineEmbedder bge = OfflineEmbedders.bge(() -> new RecordingEncoder(OfflineEmbedders.DIMENSION));

        assertThatThrownBy(() -> bge.embedQuery("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bge.embedDocument("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bge.embedDocument(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OfflineEmbedders.bge(() -> new RecordingEncoder(3)).embedDocument("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector of 3 floats, expected 384");
    }

    @Test
    void modelIsNotLoadedByFactoryOrDimension() {
        AtomicInteger loads = new AtomicInteger();
        OfflineEmbedder bge = OfflineEmbedders.bge(() -> {
            loads.incrementAndGet();
            return new RecordingEncoder(OfflineEmbedders.DIMENSION);
        });

        assertThat(bge.dimension()).isEqualTo(384);
        assertThat(bge.name()).isNotBlank();
        assertThat(loads).hasValue(0);
        assertThat(bge.isLoaded()).isFalse();

        bge.embedQuery("x");
        bge.embedDocuments(List.of("y", "z"));
        assertThat(loads).hasValue(1);
    }

    @Test
    void concurrentFirstUseLoadsExactlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        OfflineEmbedder bge = OfflineEmbedders.bge(() -> {
            loads.incrementAndGet();
            sleep(50); // widen the race window
            return new RecordingEncoder(OfflineEmbedders.DIMENSION);
        });
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<float[]>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return bge.embedQuery("q");
                }));
            }
            start.countDown();
            for (Future<float[]> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).hasSize(384);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void failedLoadIsReportedAndRetried() {
        AtomicInteger attempts = new AtomicInteger();
        OfflineEmbedder bge = OfflineEmbedders.bge(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return new RecordingEncoder(OfflineEmbedders.DIMENSION);
        });

        assertThatThrownBy(() -> bge.embedDocument("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not load embedding model 'bge-small-en-v1.5-q'")
                .hasMessageContaining("boom");
        assertThat(bge.embedDocument("x")).hasSize(384);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void factoryReturnsASharedInstance() {
        assertThat(OfflineEmbedders.bgeSmallEnV15()).isSameAs(OfflineEmbedders.bgeSmallEnV15());
    }

    @Test
    void missingResourceFailsWithTheCoordinatesToAdd() {
        assertThatThrownBy(() -> OfflineEmbedders.requireResource("com/example/none.onnx", "com.example:no-such"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("add com.example:no-such:")
                .hasMessageContaining("as a runtime dependency");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
