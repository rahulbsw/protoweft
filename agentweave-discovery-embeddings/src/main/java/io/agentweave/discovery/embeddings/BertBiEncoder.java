package io.agentweave.discovery.embeddings;

import java.util.Objects;

/**
 * Embeds text with a BERT bi-encoder: {@code [CLS] word pieces [SEP]} truncated to the model's
 * {@value #MAX_LENGTH} positions, the {@code [CLS]} hidden state as the vector, L2-normalized.
 */
final class BertBiEncoder implements TextEncoder {

    /** Position embeddings of BERT-small/base models; {@code [CLS]} and {@code [SEP]} included. */
    static final int MAX_LENGTH = 512;

    private final WordPieceTokenizer tokenizer;
    private final OnnxModel model;

    BertBiEncoder(WordPieceTokenizer tokenizer, OnnxModel model) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public float[] embed(String text) {
        WordPieceTokenizer.Encoding encoding = tokenizer.encode(text, MAX_LENGTH);
        if (encoding.length() == 2) {
            throw new IllegalArgumentException("Text has no tokens to embed");
        }
        return normalize(model.firstVector(encoding));
    }

    static float[] normalize(float[] vector) {
        float sumSquares = 0;
        for (float v : vector) {
            sumSquares += v * v;
        }
        float norm = (float) Math.sqrt(sumSquares);
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = vector[i] / norm;
        }
        return out;
    }
}
