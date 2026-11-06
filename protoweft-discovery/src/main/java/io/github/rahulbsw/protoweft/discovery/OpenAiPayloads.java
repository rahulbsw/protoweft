package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Raw-JSON request parts for OpenAI's native tool search (Responses API), as plain maps.
 * Protoweft makes no model calls: send these with your own client.
 *
 * <p>Default flow ("our catalog searches"): send {@link #clientSearchTool()} (and, if you
 * like, {@link #deferredFunctions(DiscoveryQuery, boolean)}). When the model emits a
 * client-executed {@code tool_search_call}, answer with
 * {@link #searchOutput(String, Map, CallerIdentity)}: a {@code tool_search_output} item
 * carrying the full definitions of the hits. Resolve function-call names with
 * {@link #catalogName(String)}.
 *
 * @see <a href="https://developers.openai.com/api/docs/guides/tools-tool-search">Tool search</a>
 */
public final class OpenAiPayloads {

    private final CapabilityCatalog catalog;
    private final ProviderToolNames names;

    public OpenAiPayloads(CapabilityCatalog catalog) {
        this(catalog, new ProviderToolNames());
    }

    public OpenAiPayloads(CapabilityCatalog catalog, ProviderToolNames names) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.names = Objects.requireNonNull(names, "names");
    }

    public ProviderToolNames names() {
        return names;
    }

    /** A function definition for a capability. */
    public Map<String, Object> function(ToolSpec spec, boolean deferred) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("type", "function");
        fn.put("name", names.providerName(spec.name()));
        fn.put("description", spec.description());
        fn.put("parameters", Payloads.inputSchema(spec));
        if (deferred) {
            fn.put("defer_loading", true);
        }
        return fn;
    }

    /**
     * Every capability the filter allows as {@code defer_loading} functions, for OpenAI's
     * hosted search. With {@code namespaces}, functions are grouped per source; deferral
     * stays on the inner functions, as the API requires.
     */
    public List<Map<String, Object>> deferredFunctions(DiscoveryQuery filter, boolean namespaces) {
        List<ToolSpec> specs = Payloads.permitted(catalog, filter);
        if (!namespaces) {
            List<Map<String, Object>> out = new ArrayList<>();
            specs.forEach(s -> out.add(function(s, true)));
            return out;
        }
        Map<String, List<Map<String, Object>>> bySource = new LinkedHashMap<>();
        for (ToolSpec spec : specs) {
            String source = catalog.card(spec.name()).map(CapabilityCard::source).orElse("tools");
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(function(spec, true));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        bySource.forEach((source, fns) -> {
            Map<String, Object> ns = new LinkedHashMap<>();
            ns.put("type", "namespace");
            ns.put("name", ProviderToolNames.sanitize(source));
            ns.put("description", "Tools from " + source);
            ns.put("tools", fns);
            out.add(ns);
        });
        return out;
    }

    /** The client-executed search tool: the model asks, the catalog answers. */
    public Map<String, Object> clientSearchTool() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "tool_search");
        tool.put("execution", "client");
        tool.put("description", Payloads.SEARCH_DESCRIPTION);
        tool.put("parameters", Payloads.searchSchema());
        return tool;
    }

    /** OpenAI's hosted search over deferred functions. */
    public static Map<String, Object> hostedSearchTool() {
        return Map.of("type", "tool_search");
    }

    /** A {@code tool_search_output} item loading the given capabilities. */
    public Map<String, Object> toolSearchOutput(String callId, List<String> catalogNames) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : catalogNames) {
            catalog.schema(name).ifPresent(spec -> tools.add(function(spec, false)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "tool_search_output");
        out.put("execution", "client");
        out.put("call_id", Objects.requireNonNull(callId, "callId"));
        out.put("status", "completed");
        out.put("tools", tools);
        return out;
    }

    /** Answers a client {@code tool_search_call} by searching the catalog for {@code caller}. */
    public Map<String, Object> searchOutput(String callId, Map<String, Object> arguments, CallerIdentity caller) {
        DiscoveryResult result = catalog.search(DiscoveryTools.query(arguments, caller));
        return toolSearchOutput(callId, result.names());
    }

    public Optional<String> catalogName(String providerName) {
        return names.catalogName(providerName);
    }
}
