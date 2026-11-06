package io.agentweave.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LangChain4jEmbedderTest {

    @Test
    void queryPrefixIsAppliedToQueriesOnly() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.builder(model).queryPrefix("q: ").build();

        embedder.embedQuery("find files");
        embedder.embedDocument("files.list: lists files");
        embedder.embedDocuments(List.of("a", "b"));

        assertThat(model.allInputs()).containsExactly("q: find files", "files.list: lists files", "a", "b");
    }

    @Test
    void documentPrefixIsAppliedToDocumentsOnly() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.builder(model)
                .queryPrefix("query: ")
                .documentPrefix("passage: ")
                .build();

        embedder.embedQuery("x");
        embedder.embedDocument("y");
        embedder.embedDocuments(List.of("z"));

        assertThat(model.allInputs()).containsExactly("query: x", "passage: y", "passage: z");
    }

    @Test
    void embedDocumentsSendsOneBatchByDefault() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.of(model);

        List<float[]> vectors = embedder.embedDocuments(List.of("a", "b", "c"));

        assertThat(model.batches).containsExactly(List.of("a", "b", "c"));
        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(1)).containsExactly(RecordingEmbeddingModel.vectorFor("b", 4));
    }

    @Test
    void embedDocumentsSplitsIntoBatchesInOrder() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.builder(model).batchSize(2).build();
        List<String> texts = IntStream.range(0, 5).mapToObj(i -> "t" + i).toList();

        List<float[]> vectors = embedder.embedDocuments(texts);

        assertThat(model.batches).containsExactly(List.of("t0", "t1"), List.of("t2", "t3"), List.of("t4"));
        for (int i = 0; i < texts.size(); i++) {
            assertThat(vectors.get(i)).containsExactly(RecordingEmbeddingModel.vectorFor(texts.get(i), 4));
        }
    }

    @Test
    void embedDocumentsOfEmptyListDoesNotCallTheModel() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);

        assertThat(LangChain4jEmbedder.of(model).embedDocuments(List.of())).isEmpty();
        assertThat(model.batches).isEmpty();
    }

    @Test
    void dimensionIsAskedOnceAndCached() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(7);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.of(model);

        assertThat(embedder.dimension()).isEqualTo(7);
        assertThat(embedder.dimension()).isEqualTo(7);
        assertThat(model.dimensionCalls).hasValue(1);
    }

    @Test
    void explicitDimensionDoesNotCallTheModelAndIsEnforced() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.builder(model).dimension(8).build();

        assertThat(embedder.dimension()).isEqualTo(8);
        assertThat(model.dimensionCalls).hasValue(0);
        assertThatThrownBy(() -> embedder.embedDocument("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector of 4 floats, expected 8");
    }

    @Test
    void rejectsBlankAndNullText() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel(4);
        LangChain4jEmbedder embedder = LangChain4jEmbedder.builder(model).queryPrefix("q: ").build();

        assertThatThrownBy(() -> embedder.embedQuery("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedder.embedDocument("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedder.embedDocument(null)).isInstanceOf(NullPointerException.class);
        assertThat(model.batches).isEmpty();
    }

    @Test
    void returnsCopiesOfTheModelsVectors() {
        float[] shared = {1, 2, 3};
        EmbeddingModel caching = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream().map(s -> Embedding.from(shared)).toList());
            }
        };

        float[] vector = LangChain4jEmbedder.of(caching).embedDocument("x");
        vector[0] = 42;

        assertThat(shared).containsExactly(1, 2, 3);
    }

    @Test
    void failsWhenTheModelReturnsTheWrongNumberOfEmbeddings() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(List.of(Embedding.from(new float[] {1})));
            }
        };

        assertThatThrownBy(() -> LangChain4jEmbedder.of(broken).embedDocuments(List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned 1 embeddings for 2 inputs");
    }

    @Test
    void worksWithModelsThatOnlyOverrideDoEmbed() {
        // The newer LangChain4j provider style: doEmbed overridden, embedAll left default.
        EmbeddingModel requestStyle = new EmbeddingModel() {
            @Override
            public dev.langchain4j.model.embedding.response.EmbeddingResponse doEmbed(
                    dev.langchain4j.model.embedding.request.EmbeddingRequest request) {
                return dev.langchain4j.model.embedding.response.EmbeddingResponse.builder()
                        .embeddings(request.inputs().stream()
                                .map(i -> Embedding.from(new float[] {i.text().length(), 1}))
                                .toList())
                        .build();
            }
        };

        List<float[]> vectors = LangChain4jEmbedder.of(requestStyle).embedDocuments(List.of("ab", "abcd"));

        assertThat(vectors.get(0)).containsExactly(2, 1);
        assertThat(vectors.get(1)).containsExactly(4, 1);
    }

    @Test
    void nameDefaultsToModelNameThenClassName() {
        EmbeddingModel named = new RecordingEmbeddingModel(4) {
            @Override
            public String modelName() {
                return "text-embedding-3-small";
            }
        };

        assertThat(LangChain4jEmbedder.of(named).name()).isEqualTo("text-embedding-3-small");
        assertThat(LangChain4jEmbedder.of(new RecordingEmbeddingModel(4)).name())
                .isEqualTo("RecordingEmbeddingModel");
        assertThat(LangChain4jEmbedder.builder(named).name("custom").build().name()).isEqualTo("custom");
    }
}
