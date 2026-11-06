package io.agentweave.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.agentweave.discovery.Embedder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An {@link Embedder} backed by any LangChain4j {@link EmbeddingModel}: a hosted one the
 * application already has (OpenAI, Ollama, Vertex AI, ...) or one of LangChain4j's in-process
 * models. For an offline model without LangChain4j, see {@code agentweave-discovery-embeddings}.
 *
 * <p>Every call goes through {@link EmbeddingModel#embedAll(List)}, the one method that works
 * for both implementation styles in LangChain4j 1.23.0 (models that override {@code embedAll}
 * and models that override {@code doEmbed}). {@link #embedDocuments(List)} sends the texts in
 * batches of at most {@link Builder#batchSize(int) batchSize}.
 *
 * <p>Optional prefixes are prepended to queries and to documents respectively, for models that
 * expect an instruction (bge: a query instruction; e5: {@code "query: "} / {@code "passage: "}).
 *
 * <p>Thread-safe if the underlying model is; LangChain4j's models are.
 */
public final class LangChain4jEmbedder implements Embedder {

    private final EmbeddingModel model;
    private final String name;
    private final String queryPrefix;
    private final String documentPrefix;
    private final int batchSize;
    private volatile int dimension;

    private LangChain4jEmbedder(Builder builder) {
        this.model = builder.model;
        this.name = builder.name != null ? builder.name : defaultName(builder.model);
        this.queryPrefix = builder.queryPrefix;
        this.documentPrefix = builder.documentPrefix;
        this.batchSize = builder.batchSize;
        this.dimension = builder.dimension;
    }

    /** Adapts {@code model} with no prefixes, named after {@link EmbeddingModel#modelName()}. */
    public static LangChain4jEmbedder of(EmbeddingModel model) {
        return builder(model).build();
    }

    public static Builder builder(EmbeddingModel model) {
        return new Builder(model);
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * The vector size. Taken from the builder if set, else asked of the model once and cached
     * (a model that does not know its dimension embeds a probe text to find out).
     */
    @Override
    public int dimension() {
        int d = dimension;
        if (d == 0) {
            d = model.dimension();
            if (d <= 0) {
                throw new IllegalStateException("Embedding model '" + name + "' reported dimension " + d);
            }
            dimension = d;
        }
        return d;
    }

    @Override
    public float[] embedDocument(String text) {
        return embedOne(documentPrefix, text);
    }

    @Override
    public float[] embedQuery(String text) {
        return embedOne(queryPrefix, text);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            List<TextSegment> segments = new ArrayList<>(batch.size());
            for (String text : batch) {
                segments.add(TextSegment.from(prefixed(documentPrefix, text)));
            }
            out.addAll(embedAll(segments));
        }
        return out;
    }

    /** The adapted model. */
    public EmbeddingModel model() {
        return model;
    }

    /** The text prepended to every query, empty if none. */
    public String queryPrefix() {
        return queryPrefix;
    }

    /** The text prepended to every document, empty if none. */
    public String documentPrefix() {
        return documentPrefix;
    }

    @Override
    public String toString() {
        return "LangChain4jEmbedder[" + name + "]";
    }

    private float[] embedOne(String prefix, String text) {
        return embedAll(List.of(TextSegment.from(prefixed(prefix, text)))).get(0);
    }

    private List<float[]> embedAll(List<TextSegment> segments) {
        Response<List<Embedding>> response = model.embedAll(segments);
        List<Embedding> embeddings = response == null ? null : response.content();
        if (embeddings == null || embeddings.size() != segments.size()) {
            throw new IllegalStateException("Embedding model '" + name + "' returned "
                    + (embeddings == null ? "no" : embeddings.size()) + " embeddings for "
                    + segments.size() + " inputs");
        }
        int expected = dimension;
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (Embedding embedding : embeddings) {
            float[] vector = embedding.vector();
            if (expected != 0 && vector.length != expected) {
                throw new IllegalStateException("Embedding model '" + name + "' returned a vector of "
                        + vector.length + " floats, expected " + expected);
            }
            // Copy: a model may hand out arrays it keeps (a cache), and callers may normalize in place.
            vectors.add(vector.clone());
        }
        return vectors;
    }

    private static String prefixed(String prefix, String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Cannot embed blank text");
        }
        return prefix.isEmpty() ? text : prefix + text;
    }

    private static String defaultName(EmbeddingModel model) {
        String modelName = model.modelName();
        if (modelName == null || modelName.isBlank() || "unknown".equals(modelName)) {
            return model.getClass().getSimpleName();
        }
        return modelName;
    }

    /** Builder for {@link LangChain4jEmbedder}. */
    public static final class Builder {

        private final EmbeddingModel model;
        private String name;
        private String queryPrefix = "";
        private String documentPrefix = "";
        private int batchSize = Integer.MAX_VALUE;
        private int dimension;

        private Builder(EmbeddingModel model) {
            this.model = Objects.requireNonNull(model, "model");
        }

        /** The {@link Embedder#name()}; defaults to the model's name, else its class name. */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Text prepended to every query (not to documents). Default: none. */
        public Builder queryPrefix(String queryPrefix) {
            this.queryPrefix = Objects.requireNonNull(queryPrefix, "queryPrefix");
            return this;
        }

        /** Text prepended to every document (not to queries). Default: none. */
        public Builder documentPrefix(String documentPrefix) {
            this.documentPrefix = Objects.requireNonNull(documentPrefix, "documentPrefix");
            return this;
        }

        /** Most texts per {@code embedAll} call in {@link #embedDocuments}. Default: unlimited. */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be >= 1: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /**
         * The known vector size, so {@link #dimension()} never calls the model, and every
         * returned vector is checked against it. Default: asked of the model on first use.
         */
        public Builder dimension(int dimension) {
            if (dimension < 1) {
                throw new IllegalArgumentException("dimension must be >= 1: " + dimension);
            }
            this.dimension = dimension;
            return this;
        }

        public LangChain4jEmbedder build() {
            return new LangChain4jEmbedder(this);
        }
    }
}
