package io.agentweave.evals.discovery.retrieval;

import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;

import java.util.List;
import java.util.Map;

/**
 * Ranks catalog tools for a task. Concrete retrievers (BM25, dense, hybrid over the discovery
 * module) plug in here; the harness ships only {@link RandomRetriever} and
 * {@link OracleRetriever} to test itself.
 */
public interface Retriever {

    /** Stable name used in reports, e.g. {@code bm25} or {@code hybrid-rrf}. */
    String name();

    /**
     * Called once per catalog before any {@link #retrieve} on it, so a retriever can build its
     * index. Catalogs are identified by {@link EvalCatalog#fingerprint()}.
     */
    default void index(EvalCatalog catalog) {
    }

    /**
     * The top {@code k} tool ids ({@link io.agentweave.evals.discovery.data.EvalTool#id()}) of the
     * catalog for this task, best first; at most {@code k}, ids must be catalog members.
     */
    List<String> retrieve(EvalTask task, EvalCatalog catalog, int k);

    /**
     * Extra per-query fields for the report row of the last {@link #retrieve} on this thread (for
     * example reranking latency); empty by default.
     */
    default Map<String, Object> rowExtras() {
        return Map.of();
    }
}
