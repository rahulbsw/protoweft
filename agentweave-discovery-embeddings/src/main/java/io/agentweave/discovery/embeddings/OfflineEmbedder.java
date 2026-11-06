package io.agentweave.discovery.embeddings;

import io.agentweave.discovery.Embedder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * An {@link Embedder} over a lazily loaded {@link TextEncoder}: a fixed dimension, an optional
 * query prefix, and input checks. {@link #embedDocuments} embeds several texts in parallel on
 * the common fork-join pool; each text is still encoded on its own, so results equal single calls.
 */
final class OfflineEmbedder implements Embedder {

    private final String name;
    private final int dimension;
    private final String queryPrefix;
    private final LazyEncoder encoder;

    OfflineEmbedder(String name, int dimension, String queryPrefix, Supplier<? extends TextEncoder> loader) {
        this.name = Objects.requireNonNull(name, "name");
        this.dimension = dimension;
        this.queryPrefix = Objects.requireNonNull(queryPrefix, "queryPrefix");
        this.encoder = new LazyEncoder(name, loader);
    }

    @Override
    public String name() {
        return name;
    }

    /** Known without loading the model. */
    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embedDocument(String text) {
        return embed(checked(text));
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(queryPrefix + checked(text));
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<String> inputs = new ArrayList<>(texts.size());
        for (String text : texts) {
            inputs.add(checked(text));
        }
        if (inputs.size() <= 1) {
            return inputs.isEmpty() ? List.of() : List.of(embed(inputs.get(0)));
        }
        TextEncoder loaded = encoder.get();
        return IntStream.range(0, inputs.size())
                .parallel()
                .mapToObj(i -> checkedDimension(loaded.embed(inputs.get(i))))
                .toList();
    }

    /** The text prepended to every query, empty if none. */
    String queryPrefix() {
        return queryPrefix;
    }

    boolean isLoaded() {
        return encoder.isLoaded();
    }

    @Override
    public String toString() {
        return "OfflineEmbedder[" + name + "]";
    }

    private float[] embed(String text) {
        return checkedDimension(encoder.get().embed(text));
    }

    private float[] checkedDimension(float[] vector) {
        if (vector.length != dimension) {
            throw new IllegalStateException("Embedding model '" + name + "' returned a vector of "
                    + vector.length + " floats, expected " + dimension);
        }
        return vector;
    }

    private static String checked(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Cannot embed blank text");
        }
        return text;
    }
}
