package io.agentweave.discovery.embeddings;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * An {@link EmbeddingModel} that creates its delegate on the first embedding call, exactly once
 * even under concurrent first use. {@link #dimension()} and {@link #modelName()} are answered
 * without loading. A failed load is rethrown as {@link IllegalStateException} and retried on
 * the next call.
 */
final class LazyEmbeddingModel implements EmbeddingModel {

    private final String modelName;
    private final int dimension;
    private final Supplier<? extends EmbeddingModel> loader;
    private volatile EmbeddingModel delegate;

    LazyEmbeddingModel(String modelName, int dimension, Supplier<? extends EmbeddingModel> loader) {
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.dimension = dimension;
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        return delegate().embedAll(textSegments);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    boolean isLoaded() {
        return delegate != null;
    }

    EmbeddingModel delegate() {
        EmbeddingModel d = delegate;
        if (d == null) {
            synchronized (this) {
                d = delegate;
                if (d == null) {
                    d = load();
                    delegate = d;
                }
            }
        }
        return d;
    }

    private EmbeddingModel load() {
        EmbeddingModel loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException("Could not load embedding model '" + modelName + "': " + e, e);
        }
        return Objects.requireNonNull(loaded, "loader returned null");
    }
}
