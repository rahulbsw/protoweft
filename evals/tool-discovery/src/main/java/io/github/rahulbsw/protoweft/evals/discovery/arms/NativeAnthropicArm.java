package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.discovery.AnthropicPayloads;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogBridge;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogRetriever;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Anthropic's native tool search, with Protoweft's catalog doing the searching: the whole
 * catalog goes as {@code defer_loading: true} tools plus the non-deferred
 * {@code search_capabilities} tool ({@link AnthropicPayloads}). A search is answered with a
 * {@code tool_result} of {@code tool_reference} blocks, which makes the API load those
 * definitions; a call of any other tool is the model's selection. Sent as raw JSON through
 * {@link io.github.rahulbsw.protoweft.evals.discovery.llm.AnthropicModelClient}.
 *
 * <p>Options: {@code retriever} (default {@code catalog-hybrid}), {@code fallback} (default
 * {@code progressive}). Runs only on {@code anthropic:*} models.
 */
public final class NativeAnthropicArm implements Arm {

    static final String SYSTEM_SUFFIX = " Most tools are loaded on demand: call " + DiscoveryTools.SEARCH
            + " with what you need to do, then call the tool it loads.";

    private final CatalogRetriever retriever;
    private final int maxOutputTokens;
    private final int maxTurns;
    private final TokenEstimator estimator;

    public NativeAnthropicArm(CatalogRetriever retriever, int maxOutputTokens, int maxTurns, TokenEstimator estimator) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.maxOutputTokens = maxOutputTokens;
        this.maxTurns = maxTurns;
        this.estimator = estimator;
    }

    @Override
    public String name() {
        return "native-anthropic(" + retriever.name() + ",fallback=" + DiscoveryArmSupport.fallbackName(retriever) + ")";
    }

    @Override
    public ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client) {
        if (!client.provider().equals("anthropic")) {
            return DiscoveryArmSupport.wrongProvider(name(), task, client.provider(), "anthropic");
        }
        CatalogBridge.Built built = retriever.bridge(catalog);
        AnthropicPayloads payloads = new AnthropicPayloads(built.catalog());
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(payloads.customSearchTool());
        try {
            raw.addAll(payloads.deferredTools(DiscoveryQuery.builder("").build()));
        } catch (IllegalStateException e) { // more than 10,000 deferred tools
            return new ArmResult(name(), task.id(), List.of(), 0, 0, null, 0, 0, 0, "", false, e.getMessage(), Map.of());
        }
        DiscoveryArmSupport.SearchLog log = new DiscoveryArmSupport.SearchLog(built, task);
        List<String> loaded = new ArrayList<>();
        ToolLoop.Outcome o = ToolLoop.run(client, Prompts.SYSTEM + SYSTEM_SUFFIX, Prompts.user(task), List.of(), raw,
                maxOutputTokens, maxTurns, (call, turn) -> call.name().equals(DiscoveryTools.SEARCH)
                        ? search(call, payloads, built, log, loaded) : ToolLoop.Decision.select(), estimator);
        Map<String, Object> extras = new LinkedHashMap<>();
        log.extras(extras);
        extras.put("loaded", loaded.stream().map(built::toolId).toList());
        return DiscoveryArmSupport.result(name(), task, o, c -> new SelectedCall(
                payloads.catalogName(c.name()).map(built::toolId).orElse(catalog.toolIdForName(c.name())),
                c.name(), c.arguments(), c.argumentError()), extras);
    }

    private static ToolLoop.Decision search(ToolCall call, AnthropicPayloads payloads, CatalogBridge.Built built,
                                            DiscoveryArmSupport.SearchLog log, List<String> loaded) {
        DiscoveryResult r;
        try {
            r = log.search(call.arguments());
        } catch (RuntimeException e) {
            return ToolLoop.Decision.respond(new ToolResult(call.id(), DiscoveryArmSupport.message(e), true), List.of(), true);
        }
        loaded.addAll(r.names());
        Map<String, Object> result = payloads.toolReferenceResult(call.id(), r.names());
        // What the references expand to in context (the loaded definitions): feeds token estimates.
        StringBuilder expanded = new StringBuilder();
        for (String name : r.names()) {
            built.catalog().schema(name).ifPresent(spec -> expanded.append(Json.write(payloads.tool(spec, false))).append('\n'));
        }
        return ToolLoop.Decision.respond(new ToolResult(call.id(), expanded.toString(), false, result), List.of(), true);
    }
}
