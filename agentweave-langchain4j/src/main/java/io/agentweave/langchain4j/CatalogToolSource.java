package io.agentweave.langchain4j;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.discovery.CapabilityCard;
import io.agentweave.discovery.CapabilityCatalog;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Every capability of a catalog as one {@link ToolSource}, named {@code <source>.<tool>}: schemas
 * come from the catalog's schema cache (no source is listed again), and calls go through
 * {@link CapabilityCatalog#invoke}, which re-checks the caller's permission and the source's
 * availability.
 */
final class CatalogToolSource implements ToolSource {

    private final CapabilityCatalog catalog;

    CatalogToolSource(CapabilityCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public List<ToolSpec> listTools() {
        return catalog.cards().stream()
                .map(CapabilityCard::name)
                .sorted(Comparator.naturalOrder())
                .map(name -> catalog.schema(name).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, CallerIdentity.ANONYMOUS);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return catalog.invoke(invocation.toolName(), invocation.arguments(), caller);
    }
}
