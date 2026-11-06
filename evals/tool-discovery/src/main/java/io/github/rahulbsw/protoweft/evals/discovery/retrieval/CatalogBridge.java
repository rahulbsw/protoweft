package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.CapabilityIndex;
import io.github.rahulbsw.protoweft.discovery.FallbackPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Turns an {@link EvalCatalog} into an Protoweft {@link CapabilityCatalog}, the way a deployment
 * would ingest its servers: one fixed {@link ToolSource} per MCP server (MCPToolBench++ tools carry
 * their {@code server} in the spec metadata) or, for tools without a server, per dataset (for
 * example {@code toolret-web}). Inside a source each tool is named by its provider-safe catalog
 * name ({@link EvalCatalog#providerName}), which is unique, so capability names
 * ({@code <source>.<name>}) map back to eval tool ids without ambiguity.
 *
 * <p>The sources' tools are never executed: {@code invoke} reports a failure. Arms intercept calls
 * before they would reach a source.
 */
public final class CatalogBridge {

    /**
     * A built catalog and its name mapping.
     *
     * @param catalog       the discovery catalog
     * @param toolIds       capability name → eval tool id
     * @param capabilities  eval tool id → capability name
     */
    public record Built(CapabilityCatalog catalog, Map<String, String> toolIds, Map<String, String> capabilities) {

        public Built {
            toolIds = Collections.unmodifiableMap(new LinkedHashMap<>(toolIds));
            capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        }

        /** The eval tool id behind a capability name, or {@code null}. */
        public String toolId(String capabilityName) {
            return capabilityName == null ? null : toolIds.get(capabilityName);
        }
    }

    private CatalogBridge() {
    }

    /** The source a tool is ingested under: its MCP server, else its dataset; provider-safe. */
    public static String sourceOf(EvalTool tool) {
        Object server = tool.spec().metadata().get("server");
        String raw = server instanceof String s && !s.isBlank() && !s.equals("unknown") ? s : tool.dataset();
        String safe = raw.replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.isEmpty() ? "tools" : safe;
    }

    /**
     * Builds and ingests the catalog.
     *
     * @param index    a fresh index per call (keyword, hybrid or vector-only)
     * @param fallback the catalog's fallback policy
     */
    public static Built build(EvalCatalog catalog, Supplier<CapabilityIndex> index, FallbackPolicy fallback) {
        Map<String, List<ToolSpec>> bySource = new TreeMap<>();
        Map<String, String> toolIds = new LinkedHashMap<>();
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (EvalTool t : catalog.tools()) {
            String source = sourceOf(t);
            String name = catalog.providerName(t.id());
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(t.spec().withName(name));
            String capability = source + "." + name;
            toolIds.put(capability, t.id());
            capabilities.put(t.id(), capability);
        }
        CapabilityCatalog.Builder b = CapabilityCatalog.builder().index(index.get())
                .fallback(Objects.requireNonNull(fallback, "fallback"));
        bySource.forEach((source, specs) -> b.source(source, new FixedTools(specs)));
        return new Built(b.build(), toolIds, capabilities);
    }

    /** A fixed, never-executed tool list. */
    static final class FixedTools implements ToolSource {
        private final List<ToolSpec> specs;

        FixedTools(List<ToolSpec> specs) {
            this.specs = List.copyOf(specs);
        }

        @Override
        public List<ToolSpec> listTools() {
            return specs;
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation) {
            return ToolOutcome.failure("evaluation tools are never executed");
        }
    }
}
