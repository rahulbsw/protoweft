package io.agentweave.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns text into dense vectors for semantic capability search. Back it with any embedding
 * model (for example a LangChain4j {@code EmbeddingModel}, or the offline model in
 * {@code agentweave-discovery-embeddings}). Without an embedder, discovery is keyword-only
 * and says so in every result.
 *
 * <p>Implementations must be thread-safe and return vectors of {@link #dimension()} floats.
 * Vectors need not be normalized; the index normalizes them.
 */
public interface Embedder {

    /** A stable identifier of the model, for example {@code "bge-small-en-v1.5-q"}. */
    String name();

    int dimension();

    /** Embeds a capability document (name, summary, tags, parameters). */
    float[] embedDocument(String text);

    /**
     * Embeds a search query. Models that expect a query instruction (such as bge's
     * "Represent this sentence for searching relevant passages: ") apply it here.
     */
    default float[] embedQuery(String text) {
        return embedDocument(text);
    }

    /** Embeds many documents; override for batched models. */
    default List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embedDocument(text));
        }
        return out;
    }
}
