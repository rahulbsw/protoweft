package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import org.springframework.context.SmartLifecycle;

/**
 * Re-lists the catalog's sources once the declared connectors have started. It depends on
 * {@link ProtoweftConnectors}, so Spring's lifecycle processor starts the connectors first (same
 * phase, dependencies first), and a source whose connector failed stays unavailable.
 */
final class CatalogRefresher implements SmartLifecycle {

    private final CapabilityCatalog catalog;
    private final ProtoweftConnectors connectors;
    private volatile boolean running;

    CatalogRefresher(CapabilityCatalog catalog, ProtoweftConnectors connectors) {
        this.catalog = catalog;
        this.connectors = connectors;
    }

    @Override
    public void start() {
        if (!connectors.isRunning()) {
            connectors.start();
        }
        catalog.refresh();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
