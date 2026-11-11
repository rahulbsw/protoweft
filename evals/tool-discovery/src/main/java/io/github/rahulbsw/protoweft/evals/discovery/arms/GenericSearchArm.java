package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import io.github.rahulbsw.protoweft.discovery.DiscoverySession;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.discovery.ProviderToolNames;
import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogBridge;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.CatalogRetriever;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.HistoryPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.ResultPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The generic search-tool fallback: the model gets <b>only</b> the three {@link DiscoveryTools}
 * meta-tools. {@code search_capabilities} and {@code describe_capabilities} are answered by the
 * real Protoweft catalog (through a {@link DiscoverySession}) and counted as discovery calls;
 * {@code call_capability} is the model's selection (its {@code name} and {@code arguments}), as
 * is a direct call of a capability name. Tools are never executed.
 *
 * <p>Options: {@code retriever} (default {@code catalog-hybrid}), {@code fallback}
 * ({@code progressive}|{@code all_schemas}|{@code none}, default {@code progressive}),
 * {@code inlineSchemas} (append the full schemas of the top n hits to each search result, default
 * 0), {@code load} ({@code true}: described schemas also become directly callable tools, named
 * with {@link ProviderToolNames}; default {@code false}), {@code limit} (the minimum cards per search;
 * default the product's).
 */
public final class GenericSearchArm implements Arm {

    static final String SYSTEM_SUFFIX = " Your tools are discovered on demand: call " + DiscoveryTools.SEARCH
            + " with what you need to do, " + DiscoveryTools.DESCRIBE + " to read the input schema of the best "
            + "match, then " + DiscoveryTools.CALL + " with its name and arguments.";

    private final CatalogRetriever retriever;
    private final int inlineSchemas;
    private final boolean load;
    private final int maxOutputTokens;
    private final int maxTurns;
    private final TokenEstimator estimator;
    private final int defaultLimit;
    private final MultiTurn multiTurn;
    private final ResultPolicy resultPolicy;
    private final HistoryPolicy historyPolicy;

    public GenericSearchArm(CatalogRetriever retriever, int inlineSchemas, boolean load, int maxOutputTokens,
                            int maxTurns, TokenEstimator estimator) {
        this(retriever, inlineSchemas, load, io.github.rahulbsw.protoweft.discovery.DiscoveryTools.DEFAULT_LIMIT, maxOutputTokens,
                maxTurns, estimator);
    }

    public GenericSearchArm(CatalogRetriever retriever, int inlineSchemas, boolean load, int defaultLimit,
                            int maxOutputTokens, int maxTurns, TokenEstimator estimator) {
        this(retriever, inlineSchemas, load, defaultLimit, maxOutputTokens, maxTurns, estimator, null,
                ResultPolicy.raw(), HistoryPolicy.full());
    }

    public GenericSearchArm(CatalogRetriever retriever, int inlineSchemas, boolean load, int defaultLimit,
                            int maxOutputTokens, int maxTurns, TokenEstimator estimator, MultiTurn multiTurn,
                            ResultPolicy resultPolicy, HistoryPolicy historyPolicy) {
        this.defaultLimit = defaultLimit;
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        if (inlineSchemas < 0) {
            throw new IllegalArgumentException("inlineSchemas must be >= 0");
        }
        this.inlineSchemas = inlineSchemas;
        this.load = load;
        this.maxOutputTokens = maxOutputTokens;
        this.maxTurns = maxTurns;
        this.estimator = estimator;
        this.multiTurn = multiTurn;
        this.resultPolicy = resultPolicy;
        this.historyPolicy = historyPolicy;
    }

    @Override
    public String name() {
        return "generic-search(" + retriever.name() + ",inline=" + inlineSchemas + ",fallback="
                + DiscoveryArmSupport.fallbackName(retriever) + (load ? ",load" : "")
                + (defaultLimit == io.github.rahulbsw.protoweft.discovery.DiscoveryTools.DEFAULT_LIMIT ? "" : ",limit=" + defaultLimit) + ")"
                + Arms.shapingSuffix(multiTurn, resultPolicy, historyPolicy);
    }

    @Override
    public ArmResult run(EvalTask task, EvalCatalog catalog, ModelClient client) {
        CatalogBridge.Built built = retriever.bridge(catalog);
        DiscoverySession session = new DiscoverySession(built.catalog());
        DiscoveryTools tools = DiscoveryTools.forSession(session).withDefaultLimit(defaultLimit);
        ProviderToolNames names = new ProviderToolNames();
        DiscoveryArmSupport.SearchLog log = new DiscoveryArmSupport.SearchLog(built, task);
        List<String> described = new ArrayList<>();
        ToolLoop.MockMode mock = multiTurn == null ? null
                : multiTurn.mode(task, resultPolicy, historyPolicy, c -> fixtureCall(c, built, names, catalog));
        // Discovery spends extra turns searching/describing, so budget two turns per selection.
        int turns = multiTurn == null ? maxTurns : Math.max(maxTurns, 2 * multiTurn.goldCalls(task) + 2);
        ToolLoop.Outcome o = ToolLoop.run(client, Prompts.SYSTEM + SYSTEM_SUFFIX, Prompts.user(task), tools.listTools(),
                List.of(), maxOutputTokens, turns, (call, turn) -> switch (call.name()) {
                    case DiscoveryTools.SEARCH -> search(call, tools, log, described, names);
                    case DiscoveryTools.DESCRIBE -> describe(call, call.arguments(), tools, described, names, "");
                    default -> ToolLoop.Decision.select(); // call_capability, or a direct call
                }, estimator, mock);
        Map<String, Object> extras = new LinkedHashMap<>();
        log.extras(extras);
        extras.put("described", described.stream().map(built::toolId).toList());
        return DiscoveryArmSupport.result(name(), task, o, c -> selected(c, built, names, catalog), extras);
    }

    private ToolLoop.Decision search(ToolCall call, DiscoveryTools tools, DiscoveryArmSupport.SearchLog log,
                                     List<String> described, ProviderToolNames names) {
        try {
            DiscoveryResult r = log.search(call.arguments(), defaultLimit);
            // The product's own model-facing text for the same search (deterministic, same hits).
            String text = tools.invoke(ToolInvocation.of(DiscoveryTools.SEARCH, call.arguments())).text();
            List<String> top = r.names().stream().limit(inlineSchemas).toList();
            if (top.isEmpty()) {
                return ToolLoop.Decision.respond(new ToolResult(call.id(), text, false), List.of(), true);
            }
            return describe(call, Map.of("names", top), tools, described, names, text + "\n\nInput schemas:\n");
        } catch (RuntimeException e) {
            return ToolLoop.Decision.respond(new ToolResult(call.id(), DiscoveryArmSupport.message(e), true), List.of(), true);
        }
    }

    private ToolLoop.Decision describe(ToolCall call, Map<String, Object> args, DiscoveryTools tools,
                                       List<String> described, ProviderToolNames names, String prefix) {
        try {
            ToolOutcome out = tools.invoke(ToolInvocation.of(DiscoveryTools.DESCRIBE, args));
            List<ToolSpec> loaded = new ArrayList<>();
            for (Object c : Json.asList(Json.asMap(out.structuredContent().orElse(Map.of())).get("capabilities"))) {
                Map<String, Object> cap = Json.asMap(c);
                String capability = String.valueOf(cap.get("name"));
                described.add(capability);
                if (load) {
                    loaded.add(new ToolSpec(names.providerName(capability), String.valueOf(cap.get("description")),
                            Json.asMap(cap.get("input_schema"))));
                }
            }
            return ToolLoop.Decision.respond(new ToolResult(call.id(), prefix + out.text(), false), loaded, true);
        } catch (RuntimeException e) {
            return ToolLoop.Decision.respond(new ToolResult(call.id(), DiscoveryArmSupport.message(e), true), List.of(), true);
        }
    }

    /**
     * The logical call used for fixture lookup: the selected capability's bare tool name and its real
     * arguments (unwrapping {@code call_capability} and resolving loaded provider names), keeping the
     * original call id so the mocked result answers the right call. Without this, a discovery call
     * looks up {@code call_capability} + wrapper args and misses fixtures that {@code all-tools} hits.
     */
    static ToolCall fixtureCall(ToolCall c, CatalogBridge.Built built, ProviderToolNames names, EvalCatalog catalog) {
        SelectedCall s = selected(c, built, names, catalog);
        var tool = s.toolId() == null ? null : catalog.tool(s.toolId());
        String name = tool != null ? tool.spec().name() : s.name();
        return ToolCall.of(c.id(), name, s.arguments());
    }

    /** {@code call_capability(name, arguments)} or a direct call, mapped back to an eval tool id. */
    static SelectedCall selected(ToolCall c, CatalogBridge.Built built, ProviderToolNames names, EvalCatalog catalog) {
        if (!c.name().equals(DiscoveryTools.CALL)) {
            return new SelectedCall(resolve(c.name(), built, names, catalog), c.name(), c.arguments(), c.argumentError());
        }
        String target = c.arguments().get("name") instanceof String s ? s : null;
        Object rawArgs = c.arguments().get("arguments");
        Map<String, Object> args = Map.of();
        String error = c.argumentError();
        if (rawArgs instanceof Map<?, ?> m) {
            args = Json.asMap(m);
        } else if (rawArgs instanceof String s && !s.isBlank()) {
            try {
                args = Json.parseObject(s); // some models send the arguments object as a JSON string
            } catch (RuntimeException e) {
                error = "call_capability arguments are not a JSON object";
            }
        } else if (rawArgs != null) {
            error = "call_capability arguments are not a JSON object";
        }
        if (target == null) {
            error = "call_capability without a name";
        }
        return new SelectedCall(target == null ? null : resolve(target, built, names, catalog),
                target == null ? DiscoveryTools.CALL : target, args, error);
    }

    /** A capability name, a provider-safe capability name or a bare catalog tool name → eval tool id. */
    static String resolve(String name, CatalogBridge.Built built, ProviderToolNames names, EvalCatalog catalog) {
        String id = names.catalogName(name).map(built::toolId).orElse(null);
        if (id == null) {
            id = built.toolId(name);
        }
        int sep = name.indexOf("__");
        if (id == null && sep > 0) {
            id = built.toolId(name.substring(0, sep) + "." + name.substring(sep + 2));
        }
        return id != null ? id : catalog.toolIdForName(name);
    }
}
