package io.agentweave.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HybridIndexTest {

    private static CapabilityIndex.Document doc(String id, String description) {
        return new CapabilityIndex.Document(id, Map.of(CapabilityIndex.Field.DESCRIPTION, description));
    }

    @Test
    void tokenizerSplitsIdentifiersAndDropsStopwords() {
        assertThat(Text.tokens("listFiles get_weather HTTPServer read-file.v2"))
                .containsExactly("list", "file", "get", "weather", "http", "server", "read", "file", "v2");
        assertThat(Text.tokens("Get the status of my orders")).containsExactly("get", "status", "order");
        assertThat(Text.tokens("queries classes boxes class")).containsExactly("query", "class", "box", "class");
    }

    @Test
    void summaryIsTheFirstSentence() {
        assertThat(Text.summary("Reads a file. Large files are streamed.", 160)).isEqualTo("Reads a file.");
        assertThat(Text.summary("x".repeat(200), 20)).hasSize(20).endsWith("…");
    }

    @Test
    void bm25MatchesTheHandComputedScore() {
        HybridIndex index = new HybridIndex();
        index.upsert(List.of(doc("d1", "apple banana"), doc("d2", "apple"), doc("d3", "cherry")));

        CapabilityIndex.Result r = index.search("banana", id -> true, 10, 0);

        // idf = ln(1 + (3-1+0.5)/(1+0.5)); avgdl = 4/3; norm = 1.2*(0.25 + 0.75*2/(4/3)); 2.2*idf/(1+norm)
        double idf = Math.log(1 + 2.5 / 1.5);
        double expected = idf * 2.2 / (1 + 1.2 * (0.25 + 0.75 * 2 / (4.0 / 3)));
        assertThat(r.hits()).hasSize(1);
        assertThat(r.hits().get(0).id()).isEqualTo("d1");
        assertThat(r.hits().get(0).keywordScore()).isCloseTo(expected, within(1e-9));
        assertThat(r.hits().get(0).coverage()).isEqualTo(1.0);
        assertThat(r.semantic()).isFalse();
    }

    @Test
    void fieldWeightsFavorNameMatches() {
        HybridIndex index = new HybridIndex();
        index.upsert(List.of(
                new CapabilityIndex.Document("byName", Map.of(CapabilityIndex.Field.NAME, "weather")),
                new CapabilityIndex.Document("byDescription", Map.of(CapabilityIndex.Field.DESCRIPTION, "weather"))));
        assertThat(index.search("weather", id -> true, 10, 0).hits().get(0).id()).isEqualTo("byName");
    }

    @Test
    void candidatesFilterBeforeRanking() {
        HybridIndex index = new HybridIndex();
        index.upsert(List.of(doc("a", "weather"), doc("b", "weather")));
        assertThat(index.search("weather", "b"::equals, 10, 0).hits()).extracting(CapabilityIndex.Hit::id)
                .containsExactly("b");
    }

    @Test
    void reciprocalRankFusionCombinesBothRankings() {
        Embedder fake = new FakeEmbedder(Map.of(
                "kw", new float[]{0, 1}, "vec", new float[]{1, 0}, "both", new float[]{0.9f, 0.1f}));
        HybridIndex index = new HybridIndex(fake);
        index.upsert(List.of(
                new CapabilityIndex.Document("kw", Map.of(CapabilityIndex.Field.NAME, "kw", CapabilityIndex.Field.DESCRIPTION, "forecast")),
                new CapabilityIndex.Document("vec", Map.of(CapabilityIndex.Field.NAME, "vec")),
                new CapabilityIndex.Document("both", Map.of(CapabilityIndex.Field.NAME, "both", CapabilityIndex.Field.DESCRIPTION, "forecast tomorrow"))));

        CapabilityIndex.Result r = index.search("forecast", id -> true, 10, 0.0);

        assertThat(r.semantic()).isTrue();
        CapabilityIndex.Hit top = r.hits().get(0);
        assertThat(top.id()).isEqualTo("both");
        assertThat(top.score()).isCloseTo(HybridIndex.rrf(top.keywordRank()) + HybridIndex.rrf(top.vectorRank()),
                within(1e-12));
        assertThat(HybridIndex.rrf(1)).isEqualTo(1.0 / 61);
    }

    @Test
    void vectorOnlyMatchesBelowTheFloorAreDropped() {
        Embedder fake = new FakeEmbedder(Map.of("near", new float[]{1, 0}, "far", new float[]{0, 1}));
        HybridIndex index = new HybridIndex(fake);
        index.upsert(List.of(new CapabilityIndex.Document("near", Map.of(CapabilityIndex.Field.NAME, "near")),
                new CapabilityIndex.Document("far", Map.of(CapabilityIndex.Field.NAME, "far"))));

        assertThat(index.search("zzz", id -> true, 10, 0.5).hits()).extracting(CapabilityIndex.Hit::id)
                .containsExactly("near");
    }

    @Test
    void upsertReplacesAndRemoveDeletes() {
        HybridIndex index = new HybridIndex();
        index.upsert(List.of(doc("a", "weather")));
        index.upsert(List.of(doc("a", "stocks")));
        assertThat(index.search("weather", id -> true, 10, 0).hits()).isEmpty();
        index.remove(List.of("a"));
        assertThat(index.search("stocks", id -> true, 10, 0).hits()).isEmpty();
    }

    /** Embeds by looking up the first word of the text; the query "forecast" maps to (1, 0.2). */
    static final class FakeEmbedder implements Embedder {
        private final Map<String, float[]> vectors;
        int documentCalls;

        FakeEmbedder(Map<String, float[]> vectors) {
            this.vectors = vectors;
        }

        public String name() {
            return "fake";
        }

        public int dimension() {
            return 2;
        }

        public float[] embedDocument(String text) {
            documentCalls++;
            String first = text.split("[ .]")[0];
            return vectors.getOrDefault(first, new float[]{0.01f, 0.01f});
        }

        @Override
        public float[] embedQuery(String text) {
            return text.equals("forecast") ? new float[]{1, 0.2f} : new float[]{1, 0};
        }
    }
}
