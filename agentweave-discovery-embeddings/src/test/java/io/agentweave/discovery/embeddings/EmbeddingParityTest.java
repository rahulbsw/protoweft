package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import io.agentweave.discovery.Embedder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link OfflineEmbedders#bgeSmallEnV15()} gives the vectors LangChain4j's in-process bge model
 * gives for the same text (queries with the bge prefix on both sides).
 */
class EmbeddingParityTest {

    private static final double MIN_COSINE = 0.9999;
    private static final Embedder BGE = OfflineEmbedders.bgeSmallEnV15();

    @Test
    void trickyTextsMatch() {
        List<String> texts = ParityCorpus.tricky();

        DoubleSummaryStatistics documents = compareDocuments(texts);
        DoubleSummaryStatistics queries = compareQueries(texts);

        report("tricky texts", documents, queries);
        assertThat(documents.getMin()).isGreaterThanOrEqualTo(MIN_COSINE);
        assertThat(queries.getMin()).isGreaterThanOrEqualTo(MIN_COSINE);
    }

    @Test
    void evalDatasetSampleMatches() {
        Optional<List<String>> corpus = ParityCorpus.evalTexts();
        assumeTrue(corpus.isPresent(), "eval datasets not downloaded (" + ParityCorpus.DATA_PROPERTY + ")");
        List<String> sample = ParityCorpus.sample(corpus.get(), 3000, 42);
        List<String> longTexts = corpus.get().stream()
                .filter(t -> t.length() < 20_000 && References.javaTokenizer().tokenIds(t).length > 510)
                .toList();
        List<String> longSample = ParityCorpus.sample(longTexts, 300, 43);

        DoubleSummaryStatistics documents = compareDocuments(sample);
        DoubleSummaryStatistics queries = compareQueries(sample.subList(0, 500));
        DoubleSummaryStatistics longDocuments = compareDocuments(longSample);

        report("eval sample", documents, queries);
        report("eval texts over 510 tokens", longDocuments, new DoubleSummaryStatistics());
        assertThat(documents.getMin()).isGreaterThanOrEqualTo(MIN_COSINE);
        assertThat(queries.getMin()).isGreaterThanOrEqualTo(MIN_COSINE);
        assertThat(longDocuments.getMin()).isGreaterThanOrEqualTo(MIN_COSINE);
    }

    @Test
    void textsOverTheWindowAreTruncatedLikeLangChain4j() {
        // LangChain4j's DJL tokenizer truncates to 512 tokens by default, so its windowed
        // averaging never sees more than one window: both keep the first 510 word pieces.
        String exactly510 = "word ".repeat(510).strip();
        String over = "word ".repeat(600) + "and a different tail";

        assertThat(BGE.embedDocument(over)).containsExactly(BGE.embedDocument(exactly510));
        assertThat(References.cosine(References.langChain4jEmbedding(over),
                References.langChain4jEmbedding(exactly510))).isGreaterThan(0.99999);
        assertThat(References.cosine(BGE.embedDocument(over), References.langChain4jEmbedding(over)))
                .isGreaterThanOrEqualTo(MIN_COSINE);
    }

    @Test
    void latencyComparedWithLangChain4j() {
        List<String> docs = IntStream.range(0, 100)
                .mapToObj(i -> "tool_" + i + ": reads the file at the given path and returns its lines")
                .toList();
        BGE.embedDocument("warm up");
        References.langChain4jEmbedding("warm up");

        double javaSingle = millisPerText(docs, () -> docs.forEach(BGE::embedDocument));
        double lc4jSingle = millisPerText(docs, () -> docs.forEach(References::langChain4jEmbedding));
        double javaBatch = millisPerText(docs, () -> BGE.embedDocuments(docs));
        double lc4jBatch = millisPerText(docs, () -> References.langChain4jModel()
                .embedAll(docs.stream().map(TextSegment::from).toList()));

        System.out.printf("[timing] per embedding: %.2f ms (this module) vs %.2f ms (LangChain4j); batch of 100:"
                        + " %.2f vs %.2f ms per text (%d cores)%n",
                javaSingle, lc4jSingle, javaBatch, lc4jBatch, Runtime.getRuntime().availableProcessors());
    }

    private static double millisPerText(List<String> texts, Runnable run) {
        long start = System.nanoTime();
        run.run();
        return (System.nanoTime() - start) / 1e6 / texts.size();
    }

    private static DoubleSummaryStatistics compareDocuments(List<String> texts) {
        return compare(BGE.embedDocuments(texts), texts);
    }

    private static DoubleSummaryStatistics compareQueries(List<String> texts) {
        List<float[]> ours = texts.stream().map(BGE::embedQuery).toList();
        return compare(ours, texts.stream().map(t -> OfflineEmbedders.BGE_QUERY_PREFIX + t).toList());
    }

    private static DoubleSummaryStatistics compare(List<float[]> ours, List<String> theirInputs) {
        List<Embedding> theirs = References.langChain4jModel()
                .embedAll(theirInputs.stream().map(TextSegment::from).toList()).content();
        DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
        List<String> worst = new ArrayList<>();
        int identical = 0;
        for (int i = 0; i < ours.size(); i++) {
            double cosine = References.cosine(ours.get(i), theirs.get(i).vector());
            stats.accept(cosine);
            if (Arrays.equals(ours.get(i), theirs.get(i).vector())) {
                identical++;
            }
            if (cosine < MIN_COSINE) {
                worst.add(String.format("%.6f '%s'", cosine, abbreviate(theirInputs.get(i))));
            }
        }
        if (!worst.isEmpty()) {
            System.out.println("[parity] below " + MIN_COSINE + ": " + worst);
        }
        System.out.printf("[parity] %d of %d vectors bit-identical%n", identical, ours.size());
        return stats;
    }

    private static void report(String what, DoubleSummaryStatistics documents, DoubleSummaryStatistics queries) {
        System.out.printf("[parity] vectors, %s: %d documents (min cosine %.9f, mean %.9f), %d queries (min %.9f)%n",
                what, documents.getCount(), documents.getMin(), documents.getAverage(), queries.getCount(),
                queries.getCount() == 0 ? Double.NaN : queries.getMin());
    }

    private static String abbreviate(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
