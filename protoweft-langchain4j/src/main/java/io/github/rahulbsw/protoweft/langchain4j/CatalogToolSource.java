package io.github.rahulbsw.protoweft.langchain4j;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.CapabilityCard;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;

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
