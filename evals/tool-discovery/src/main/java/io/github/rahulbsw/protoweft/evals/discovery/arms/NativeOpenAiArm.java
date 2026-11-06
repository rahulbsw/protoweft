package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import io.github.rahulbsw.protoweft.discovery.OpenAiPayloads;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.OpenAiModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogBridge;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogRetriever;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI's native tool search in client-executed mode, with Protoweft's catalog doing the
 * searching: the request carries the client {@code tool_search} tool ({@link OpenAiPayloads});
 * each {@code tool_search_call} is answered with a {@code tool_search_output} item holding the
 * hits' full function definitions, which become callable; a function call is the model's
 * selection. Sent as raw JSON through {@link OpenAiModelClient}.
 *
 * <p>Options: {@code retriever} (default {@code catalog-hybrid}), {@code fallback} (default
 * {@code progressive}), {@code deferred=none|functions|namespaces} (also declare the catalog as
 * {@code defer_loading} functions, optionally grouped per source; default {@code none}: the model
 * sees only the search tool). Runs only on {@code openai:*} models.
 */
public final class NativeOpenAiArm implements Arm {

    static final String SYSTEM_SUFFIX = " Tools are loaded on demand: use tool search with what you need to do, "
            + "then call the tool it loads.";

    private final CatalogRetriever retriever;
    private final String deferred;
    private final int maxOutputTokens;
    private final int maxTurns;
    private final TokenEstimator estimator;

    public NativeOpenAiArm(CatalogRetriever retriever, String deferred, int maxOutputTokens, int maxTurns,
                           TokenEstimator estimator) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.deferred = deferred.toLowerCase(Locale.ROOT);
        if (!List.of("none", "functions", "namespaces").contains(this.deferred)) {
            throw new IllegalArgumentException("deferred must be none, functions or namespaces: " + deferred);
        }
        this.maxOutputTokens = maxOutputTokens;
        this.maxTurns = maxTurns;
        this.estimator = estimator;
    }

    @Override
    public String name() {
        return "native-openai(" + retriever.name() + ",fallback=" + DiscoveryArmSupport.fallbackName(retriever)
                + (deferred.equals("none") ? "" : ",deferred=" + deferred) + ")";
    }

    @Override
    public ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client) {
        if (!client.provider().equals("openai")) {
            return DiscoveryArmSupport.wrongProvider(name(), task, client.provider(), "openai");
        }
        CatalogBridge.Built built = retriever.bridge(catalog);
        OpenAiPayloads payloads = new OpenAiPayloads(built.catalog());
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(payloads.clientSearchTool());
        if (!deferred.equals("none")) {
            raw.addAll(payloads.deferredFunctions(DiscoveryQuery.builder("").build(), deferred.equals("namespaces")));
        }
        DiscoveryArmSupport.SearchLog log = new DiscoveryArmSupport.SearchLog(built, task);
        List<String> loaded = new ArrayList<>();
        ToolLoop.Outcome o = ToolLoop.run(client, Prompts.SYSTEM + SYSTEM_SUFFIX, Prompts.user(task), List.of(), raw,
                maxOutputTokens, maxTurns, (call, turn) -> call.name().equals(OpenAiModelClient.TOOL_SEARCH_CALL)
                        ? search(call, payloads, log, loaded) : ToolLoop.Decision.select(), estimator);
        Map<String, Object> extras = new LinkedHashMap<>();
        log.extras(extras);
        extras.put("loaded", loaded.stream().map(built::toolId).toList());
        return DiscoveryArmSupport.result(name(), task, o, c -> new SelectedCall(
                payloads.catalogName(c.name()).map(built::toolId).orElse(catalog.toolIdForName(c.name())),
                c.name(), c.arguments(), c.argumentError()), extras);
    }

    private static ToolLoop.Decision search(ToolCall call, OpenAiPayloads payloads, DiscoveryArmSupport.SearchLog log,
                                            List<String> loaded) {
        List<String> names;
        boolean error = false;
        try {
            DiscoveryResult r = log.search(call.arguments());
            names = r.names();
        } catch (RuntimeException e) {
            names = List.of(); // a tool_search_call must be answered with a tool_search_output
            error = true;
        }
        loaded.addAll(names);
        Map<String, Object> output = payloads.toolSearchOutput(call.id(), names);
        return ToolLoop.Decision.respond(new ToolResult(call.id(), Json.write(output.get("tools")), error, output),
                List.of(), true);
    }
}
