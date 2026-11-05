package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The offline configurations over a fake model: prefixes and lazy, once-only loading. */
class OfflineEmbeddersConfigTest {

    @Test
    void bgePrefixesQueriesButNotDocuments() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(OfflineEmbedders.DIMENSION);
        LangChain4jEmbedder bge = OfflineEmbedders.bge(() -> model);

        bge.embedQuery("list files in a directory");
        bge.embedDocument("fs.list_dir: List the contents of a folder");
        bge.embedDocuments(List.of("weather.current: Get the current weather"));

        assertThat(model.allInputs()).containsExactly(
                "Represent this sentence for searching relevant passages: list files in a directory",
                "fs.list_dir: List the contents of a folder",
                "weather.current: Get the current weather");
        assertThat(bge.name()).isEqualTo("bge-small-en-v1.5-q");
        assertThat(bge.queryPrefix()).isEqualTo(OfflineEmbedders.BGE_QUERY_PREFIX);
    }

    @Test
    void miniLmHasNoPrefixes() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(OfflineEmbedders.DIMENSION);
        LangChain4jEmbedder miniLm = OfflineEmbedders.miniLm(() -> model);

        miniLm.embedQuery("q");
        miniLm.embedDocument("d");

        assertThat(model.allInputs()).containsExactly("q", "d");
        assertThat(miniLm.name()).isEqualTo("all-minilm-l6-v2-q");
    }

    @Test
    void modelIsNotLoadedByFactoryOrDimension() {
        AtomicInteger loads = new AtomicInteger();
        LangChain4jEmbedder bge = OfflineEmbedders.bge(() -> {
            loads.incrementAndGet();
            return new RecordingEmbeddingModel(OfflineEmbedders.DIMENSION);
        });

        assertThat(bge.dimension()).isEqualTo(384);
        assertThat(bge.name()).isNotBlank();
        assertThat(loads).hasValue(0);

        bge.embedQuery("x");
        bge.embedDocument("y");
        assertThat(loads).hasValue(1);
    }

    @Test
    void concurrentFirstUseLoadsExactlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        LangChain4jEmbedder bge = OfflineEmbedders.bge(() -> {
            loads.incrementAndGet();
            sleep(50); // widen the race window
            return new RecordingEmbeddingModel(OfflineEmbedders.DIMENSION);
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
        LangChain4jEmbedder bge = OfflineEmbedders.bge(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return new RecordingEmbeddingModel(OfflineEmbedders.DIMENSION);
        });

        assertThatThrownBy(() -> bge.embedDocument("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not load embedding model 'bge-small-en-v1.5-q'")
                .hasMessageContaining("boom");
        assertThat(bge.embedDocument("x")).hasSize(384);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void factoriesReturnSharedInstances() {
        assertThat(OfflineEmbedders.bgeSmallEnV15()).isSameAs(OfflineEmbedders.bgeSmallEnV15());
        assertThat(OfflineEmbedders.allMiniLmL6V2()).isSameAs(OfflineEmbedders.allMiniLmL6V2());
    }

    @Test
    void missingArtifactFailsWithTheCoordinatesToAdd() {
        assertThatThrownBy(() -> OfflineEmbedders.requireOnClasspath("com.example.NoSuchModel", "com.example:no-such"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.example:no-such:1.23.0-beta33");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
