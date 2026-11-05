package io.agentweave.discovery;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Raw-JSON request parts for Anthropic's native tool search (Messages API), as plain maps.
 * AgentWeave makes no model calls: send these with your own client.
 *
 * <p>Default flow ("our catalog searches"): send {@link #deferredTools(DiscoveryQuery)}
 * plus {@link #customSearchTool()}. When the model calls the search tool, answer with
 * {@link #searchResult(String, Map, CallerIdentity)}: a {@code tool_result} of
 * {@code tool_reference} blocks, which makes Anthropic load those deferred schemas. Then
 * resolve the model's {@code tool_use} names with {@link #catalogName(String)}.
 *
 * @see <a href="https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool">Tool search tool</a>
 */
public final class AnthropicPayloads {

    /** Anthropic's hosted search variants. */
    public enum HostedSearch { REGEX, BM25 }

    /** Anthropic limit on deferred tools per request. */
    public static final int MAX_DEFERRED_TOOLS = 10_000;

    private final CapabilityCatalog catalog;
    private final ProviderToolNames names;

    public AnthropicPayloads(CapabilityCatalog catalog) {
        this(catalog, new ProviderToolNames());
    }

    public AnthropicPayloads(CapabilityCatalog catalog, ProviderToolNames names) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.names = Objects.requireNonNull(names, "names");
    }

    public ProviderToolNames names() {
        return names;
    }

    /**
     * Every capability the query's filters allow (caller permissions, availability,
     * protocols…), as {@code defer_loading} tool definitions. The query text and limit are
     * ignored. Deferred tools carry no {@code cache_control}, as Anthropic requires.
     */
    public List<Map<String, Object>> deferredTools(DiscoveryQuery filter) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec spec : Payloads.permitted(catalog, filter)) {
            tools.add(tool(spec, true));
        }
        if (tools.size() > MAX_DEFERRED_TOOLS) {
            throw new IllegalStateException("Anthropic accepts at most " + MAX_DEFERRED_TOOLS
                    + " deferred tools; narrow the filter (got " + tools.size() + ")");
        }
        return tools;
    }

    /** A non-deferred tool definition of a capability, e.g. for tools that must always be visible. */
    public Map<String, Object> tool(ToolSpec spec, boolean deferred) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", names.providerName(spec.name()));
        tool.put("description", spec.description());
        tool.put("input_schema", Payloads.inputSchema(spec));
        if (deferred) {
            tool.put("defer_loading", true);
        }
        return tool;
    }

    /** The catalog-backed search tool: a normal (never deferred) tool. */
    public Map<String, Object> customSearchTool() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", DiscoveryTools.SEARCH);
        tool.put("description", Payloads.SEARCH_DESCRIPTION);
        tool.put("input_schema", Payloads.searchSchema());
        return tool;
    }

    /** Anthropic's hosted search tool, for when the provider should do the searching. */
    public static Map<String, Object> hostedSearchTool(HostedSearch variant) {
        return switch (variant) {
            case REGEX -> Map.of("type", "tool_search_tool_regex_20251119", "name", "tool_search_tool_regex");
            case BM25 -> Map.of("type", "tool_search_tool_bm25_20251119", "name", "tool_search_tool_bm25");
        };
    }

    /** A {@code tool_result} referencing catalog capabilities, so Anthropic loads their schemas. */
    public Map<String, Object> toolReferenceResult(String toolUseId, List<String> catalogNames) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String name : catalogNames) {
            refs.add(Map.of("type", "tool_reference", "tool_name", names.providerName(name)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "tool_result");
        result.put("tool_use_id", Objects.requireNonNull(toolUseId, "toolUseId"));
        result.put("content", refs);
        return result;
    }

    /**
     * Answers a {@code search_capabilities} call: runs the catalog search for {@code caller}
     * and references the hits (including broadened hits on low confidence).
     */
    public Map<String, Object> searchResult(String toolUseId, Map<String, Object> input, CallerIdentity caller) {
        DiscoveryResult result = catalog.search(DiscoveryTools.query(input, caller));
        return toolReferenceResult(toolUseId, result.names());
    }

    /** Maps a provider tool name from a {@code tool_use} block back to the catalog name. */
    public java.util.Optional<String> catalogName(String providerName) {
        return names.catalogName(providerName);
    }
}
