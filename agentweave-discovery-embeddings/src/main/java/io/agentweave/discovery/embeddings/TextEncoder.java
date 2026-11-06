package io.agentweave.discovery.embeddings;

/** Turns one text into one vector; implementations are thread-safe. */
@FunctionalInterface
interface TextEncoder {

    /**
     * @throws IllegalArgumentException if the text yields no tokens
     */
    float[] embed(String text);
}
