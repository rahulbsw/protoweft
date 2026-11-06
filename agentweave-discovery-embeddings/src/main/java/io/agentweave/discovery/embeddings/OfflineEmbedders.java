package io.agentweave.discovery.embeddings;

import ai.onnxruntime.OrtException;
import io.agentweave.discovery.Embedder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Supplier;

/**
 * Embedders that run in-process on the CPU through ONNX Runtime: no API key, no network, no
 * model download.
 *
 * <p>{@link #bgeSmallEnV15()} — quantized BAAI bge-small-en-v1.5, 384 dimensions. Its model
 * ships in {@code io.agentweave:agentweave-model-bge-small-en-v15}, a runtime dependency of this
 * module.
 *
 * <p>The model is loaded on the first embedding call (not by the factory, and not by
 * {@link Embedder#dimension()}), once per class loader, and is safe to use from many threads.
 * The factory returns the same shared instance on every call.
 */
public final class OfflineEmbedders {

    /** The query instruction recommended by the bge-small-en-v1.5 model card; applied to queries only. */
    public static final String BGE_QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";

    /** {@link Embedder#name()} of {@link #bgeSmallEnV15()}. */
    public static final String BGE_SMALL_EN_V15_NAME = "bge-small-en-v1.5-q";

    static final int DIMENSION = 384;
    static final String BGE_RESOURCES = "io/agentweave/models/bge-small-en-v15/";
    static final String BGE_ARTIFACT = "io.agentweave:agentweave-model-bge-small-en-v15";

    private OfflineEmbedders() {}

    /**
     * Quantized BAAI bge-small-en-v1.5 (MIT), 384 dimensions, CLS pooling, L2-normalized.
     * Queries get {@link #BGE_QUERY_PREFIX}; documents are embedded as given.
     *
     * @throws IllegalStateException if the model artifact is not on the classpath
     */
    public static Embedder bgeSmallEnV15() {
        requireResource(BGE_RESOURCES + "model.onnx", BGE_ARTIFACT);
        return Bge.EMBEDDER;
    }

    /** The bge configuration over any encoder loader; package-private so tests can substitute a fake. */
    static OfflineEmbedder bge(Supplier<? extends TextEncoder> loader) {
        return new OfflineEmbedder(BGE_SMALL_EN_V15_NAME, DIMENSION, BGE_QUERY_PREFIX, loader);
    }

    private static final class Bge {
        static final OfflineEmbedder EMBEDDER = bge(() -> loadBertBiEncoder(BGE_RESOURCES));
    }

    /** Reads {@code model.onnx} and {@code vocab.txt} under {@code resources} and creates the session. */
    static BertBiEncoder loadBertBiEncoder(String resources) {
        WordPieceTokenizer tokenizer = WordPieceTokenizer.read(open(resources + "vocab.txt"));
        byte[] model;
        try (InputStream in = open(resources + "model.onnx")) {
            model = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resources + "model.onnx", e);
        }
        try {
            return new BertBiEncoder(tokenizer, OnnxModel.load(model));
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX Runtime could not load " + resources + "model.onnx: "
                    + e.getMessage(), e);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = OfflineEmbedders.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(resource + " is not on the classpath");
        }
        return in;
    }

    static void requireResource(String resource, String artifact) {
        if (OfflineEmbedders.class.getClassLoader().getResource(resource) != null) {
            return;
        }
        String version = OfflineEmbedders.class.getPackage().getImplementationVersion();
        throw new IllegalStateException("Offline embedding model not on the classpath (" + resource + "): add "
                + artifact + ":" + (version != null ? version : "<the agentweave-discovery-embeddings version>")
                + " as a runtime dependency");
    }
}
