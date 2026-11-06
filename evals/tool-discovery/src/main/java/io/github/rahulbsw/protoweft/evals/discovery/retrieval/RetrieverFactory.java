package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import java.util.Map;

/**
 * Service-provider hook for retrievers, discovered with {@link java.util.ServiceLoader} from
 * {@code META-INF/services/io.github.rahulbsw.protoweft.evals.discovery.retrieval.RetrieverFactory}, so the
 * discovery integration can add retrievers (BM25, dense, hybrid) without touching the CLI.
 * The built-in names {@code random} and {@code oracle} are always available.
 */
public interface RetrieverFactory {

    /** Names this factory can build, e.g. {@code bm25}, {@code hybrid}. */
    java.util.Set<String> names();

    /**
     * Builds a retriever.
     *
     * @param name    one of {@link #names()}
     * @param options free-form {@code key=value} options from the CLI ({@code --retriever-opt}),
     *                plus {@code seed}
     */
    Retriever create(String name, Map<String, String> options);
}
