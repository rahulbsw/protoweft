package io.agentweave.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake model: records every embedAll batch and returns a vector derived from the text. */
class RecordingEmbeddingModel implements EmbeddingModel {

    final List<List<String>> batches = new CopyOnWriteArrayList<>();
    final AtomicInteger dimensionCalls = new AtomicInteger();
    private final int dimension;

    RecordingEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = new ArrayList<>();
        List<Embedding> out = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            texts.add(segment.text());
            out.add(Embedding.from(vectorFor(segment.text(), dimension)));
        }
        batches.add(texts);
        return Response.from(out);
    }

    @Override
    public int dimension() {
        dimensionCalls.incrementAndGet();
        return dimension;
    }

    List<String> allInputs() {
        List<String> all = new ArrayList<>();
        batches.forEach(all::addAll);
        return all;
    }

    static float[] vectorFor(String text, int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = (text.hashCode() >>> (i % 31)) & 0xFF;
        }
        return v;
    }
}
