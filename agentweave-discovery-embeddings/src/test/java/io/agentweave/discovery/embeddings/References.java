package io.agentweave.discovery.embeddings;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The references the pure-Java implementation is compared with: DJL's binding of HuggingFace's
 * Rust {@code tokenizers} over the bge {@code tokenizer.json}, and LangChain4j's in-process bge
 * model (the same ONNX file), configured as LangChain4j configures them.
 */
final class References {

    static final String TOKENIZER_JSON = "bge-small-en-v1.5-q-tokenizer.json";

    private References() {}

    /** As LangChain4j creates it: no padding, otherwise defaults (truncation to 512, longest first). */
    static HuggingFaceTokenizer langChain4jTokenizer() {
        return Tokenizers.LANGCHAIN4J;
    }

    /** Untruncated, for comparing whole token sequences. */
    static HuggingFaceTokenizer untruncatedTokenizer() {
        return Tokenizers.UNTRUNCATED;
    }

    static WordPieceTokenizer javaTokenizer() {
        return Java.TOKENIZER;
    }

    static EmbeddingModel langChain4jModel() {
        return LangChain4j.MODEL;
    }

    static float[] langChain4jEmbedding(String text) {
        return langChain4jModel().embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static final class Java {
        static final WordPieceTokenizer TOKENIZER = WordPieceTokenizer.read(
                OfflineEmbedders.class.getClassLoader().getResourceAsStream(OfflineEmbedders.BGE_RESOURCES + "vocab.txt"));
    }

    private static final class LangChain4j {
        static final EmbeddingModel MODEL = new BgeSmallEnV15QuantizedEmbeddingModel();
    }

    private static final class Tokenizers {
        static final HuggingFaceTokenizer LANGCHAIN4J = djl(Map.of("padding", "false"));
        static final HuggingFaceTokenizer UNTRUNCATED = djl(Map.of("padding", "false", "truncation", "false"));

        private static HuggingFaceTokenizer djl(Map<String, String> options) {
            try (InputStream json = References.class.getClassLoader().getResourceAsStream(TOKENIZER_JSON)) {
                return HuggingFaceTokenizer.newInstance(json, options);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
