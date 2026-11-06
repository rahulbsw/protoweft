package io.agentweave.evals.discovery.arms;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.DiscoveryTools;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.llm.ToolCall;
import io.agentweave.evals.discovery.retrieval.CatalogBridge;
import io.agentweave.evals.discovery.retrieval.CatalogRetriever;
import io.agentweave.evals.discovery.retrieval.DiscoveryRetrieverFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Shared pieces of the discovery arms: catalog setup, search logging and result building. */
final class DiscoveryArmSupport {

    /** Default retriever of the discovery arms: the product's hybrid catalog with the offline embedder. */
    static final String DEFAULT_RETRIEVER = DiscoveryRetrieverFactory.HYBRID;

    private DiscoveryArmSupport() {
    }

    /**
     * The arm's catalog retriever: {@code retriever=<name>} (default {@code catalog-hybrid}) with
     * {@code fallback=<mode>} (default {@code progressive}, the product default).
     */
    static CatalogRetriever retriever(Map<String, String> options) {
        Map<String, String> ropts = new LinkedHashMap<>(options);
        ropts.put("fallback", options.getOrDefault("fallback", "progressive"));
        return DiscoveryRetrieverFactory.catalogRetriever(options.getOrDefault("retriever", DEFAULT_RETRIEVER), ropts);
    }

    static String fallbackName(CatalogRetriever r) {
        return r.fallback().mode().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Records each discovery search for the per-call log. */
    static final class SearchLog {
        private final List<Map<String, Object>> searches = new ArrayList<>();
        private final CatalogBridge.Built built;
        private final EvalTask task;
        private boolean goldFound;

        SearchLog(CatalogBridge.Built built, EvalTask task) {
            this.built = built;
            this.task = task;
        }

        /** Runs a {@code search_capabilities}-style search; argument errors propagate. */
        DiscoveryResult search(Map<String, Object> args) {
            return search(args, io.agentweave.discovery.DiscoveryQuery.DEFAULT_LIMIT);
        }

        /** As {@link #search(Map)}, with {@code defaultLimit} when the model sets none. */
        DiscoveryResult search(Map<String, Object> args, int defaultLimit) {
            DiscoveryResult r = built.catalog().search(DiscoveryTools.query(args, CallerIdentity.ANONYMOUS, defaultLimit));
            record(args.get("query"), r);
            Map<String, Object> filters = new LinkedHashMap<>(args);
            filters.remove("query");
            if (!filters.isEmpty()) {
                searches.get(searches.size() - 1).put("filters", filters);
            }
            return r;
        }

        void record(Object query, DiscoveryResult r) {
            List<String> ids = r.names().stream().map(built::toolId).filter(Objects::nonNull).toList();
            if (ids.stream().anyMatch(task::acceptsTool)) {
                goldFound = true;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("query", String.valueOf(query));
            s.put("results", ids);
            s.put("namesOnly", r.namesOnly().size());
            s.put("confidence", r.confidence().level().name());
            s.put("stage", r.stage().name());
            searches.add(s);
        }

        void extras(Map<String, Object> extras) {
            extras.put("searches", searches);
            extras.put("goldFound", goldFound);
        }
    }

    /** An error message for a discovery call the catalog rejected. */
    static String message(RuntimeException e) {
        return e instanceof AgentWeaveException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** Builds the arm result, mapping each real call with {@code mapper}. */
    static ArmResult result(String arm, EvalTask task, ToolLoop.Outcome o, Function<ToolCall, SelectedCall> mapper,
                            Map<String, Object> extras) {
        List<SelectedCall> calls = o.realCalls().stream().map(mapper).toList();
        return new ArmResult(arm, task.id(), calls, o.discoveryCalls(), o.responses().size(), o.usage(),
                o.latencyMillis(), o.toolsOffered(), o.toolPayloadTokens(), o.finalText(), o.hitMaxTurns(), o.error(),
                extras);
    }

    /** A result for a model of the wrong provider: no model call is made. */
    static ArmResult wrongProvider(String arm, EvalTask task, String provider, String expected) {
        return new ArmResult(arm, task.id(), List.of(), 0, 0, null, 0, 0, 0, "", false,
                arm + " needs a " + expected + " model, got " + provider + " (pass --models " + expected + ":<model>)",
                Map.of());
    }
}
