package io.agentweave.discovery.embeddings;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.agentweave.discovery.Embedder;
import java.util.function.Supplier;

/**
 * Embedders that run in-process on the CPU (ONNX Runtime via LangChain4j): no API key, no
 * network, no model download. Both are quantized 384-dimension English models.
 *
 * <ul>
 *   <li>{@link #bgeSmallEnV15()} — the default; its artifact ships with this module.
 *   <li>{@link #allMiniLmL6V2()} — smaller; add
 *       {@code dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.23.0-beta33} to the
 *       runtime classpath to use it.
 * </ul>
 *
 * <p>The model is loaded on the first embedding call (not by the factory, and not by
 * {@link Embedder#dimension()}), once per JVM, and is safe to use from many threads. Each
 * factory returns the same shared instance on every call.
 */
public final class OfflineEmbedders {

    /** The query instruction recommended by the bge-small-en-v1.5 model card; applied to queries only. */
    public static final String BGE_QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";

    /** {@link Embedder#name()} of {@link #bgeSmallEnV15()}. */
    public static final String BGE_SMALL_EN_V15_NAME = "bge-small-en-v1.5-q";

    /** {@link Embedder#name()} of {@link #allMiniLmL6V2()}. */
    public static final String ALL_MINILM_L6_V2_NAME = "all-minilm-l6-v2-q";

    static final int DIMENSION = 384;

    private static final String ARTIFACT_VERSION = "1.23.0-beta33";
    private static final String BGE_CLASS =
            "dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel";
    private static final String BGE_ARTIFACT = "dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q";
    private static final String MINILM_CLASS =
            "dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel";
    private static final String MINILM_ARTIFACT = "dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q";

    private OfflineEmbedders() {}

    /**
     * Quantized BAAI bge-small-en-v1.5 (MIT), 384 dimensions, CLS pooling. Queries get
     * {@link #BGE_QUERY_PREFIX}; documents are embedded as given.
     *
     * @throws IllegalStateException if the model artifact is not on the classpath
     */
    public static Embedder bgeSmallEnV15() {
        requireOnClasspath(BGE_CLASS, BGE_ARTIFACT);
        return Bge.EMBEDDER;
    }

    /**
     * Quantized sentence-transformers all-MiniLM-L6-v2 (Apache-2.0), 384 dimensions, mean
     * pooling, no query prefix. Needs its artifact on the runtime classpath.
     *
     * @throws IllegalStateException if the model artifact is not on the classpath
     */
    public static Embedder allMiniLmL6V2() {
        requireOnClasspath(MINILM_CLASS, MINILM_ARTIFACT);
        return MiniLm.EMBEDDER;
    }

    /** The bge configuration over any model loader; package-private so tests can substitute a fake. */
    static LangChain4jEmbedder bge(Supplier<? extends EmbeddingModel> loader) {
        return LangChain4jEmbedder.builder(new LazyEmbeddingModel(BGE_SMALL_EN_V15_NAME, DIMENSION, loader))
                .name(BGE_SMALL_EN_V15_NAME)
                .queryPrefix(BGE_QUERY_PREFIX)
                .dimension(DIMENSION)
                .build();
    }

    /** The all-MiniLM-L6-v2 configuration over any model loader. */
    static LangChain4jEmbedder miniLm(Supplier<? extends EmbeddingModel> loader) {
        return LangChain4jEmbedder.builder(new LazyEmbeddingModel(ALL_MINILM_L6_V2_NAME, DIMENSION, loader))
                .name(ALL_MINILM_L6_V2_NAME)
                .dimension(DIMENSION)
                .build();
    }

    // Each model class is referenced only from its own holder, so a missing optional artifact
    // fails in requireOnClasspath with a clear message instead of a NoClassDefFoundError.

    private static final class Bge {
        static final LangChain4jEmbedder EMBEDDER = bge(() -> withClassLoaderOf(BGE_CLASS, Bge::create));

        static EmbeddingModel create() {
            return new dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel();
        }
    }

    private static final class MiniLm {
        static final LangChain4jEmbedder EMBEDDER = miniLm(() -> withClassLoaderOf(MINILM_CLASS, MiniLm::create));

        static EmbeddingModel create() {
            return new dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel();
        }
    }

    /**
     * LangChain4j reads the model file through the thread context class loader when the model
     * class initializes. The first embedding may run on any thread (a pool thread, a fat-jar
     * launcher thread) whose context loader cannot see the model jar, so point it at the loader
     * that holds the model class for the duration of the load.
     */
    private static EmbeddingModel withClassLoaderOf(String className, Supplier<EmbeddingModel> create) {
        ClassLoader modelLoader = classLoaderOf(className);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(modelLoader);
        try {
            return create.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static ClassLoader classLoaderOf(String className) {
        try {
            return Class.forName(className, false, OfflineEmbedders.class.getClassLoader()).getClassLoader();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(className + " is not on the classpath", e);
        }
    }

    static void requireOnClasspath(String className, String artifact) {
        try {
            Class.forName(className, false, OfflineEmbedders.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("Offline embedding model not on the classpath: add "
                    + artifact + ":" + ARTIFACT_VERSION + " as a runtime dependency", e);
        }
    }
}
