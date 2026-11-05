package io.agentweave.spring.boot;

import io.agentweave.discovery.CapabilityCatalog;
import org.springframework.context.SmartLifecycle;

/**
 * Re-lists the catalog's sources once the declared connectors have started. It depends on
 * {@link AgentWeaveConnectors}, so Spring's lifecycle processor starts the connectors first (same
 * phase, dependencies first), and a source whose connector failed stays unavailable.
 */
final class CatalogRefresher implements SmartLifecycle {

    private final CapabilityCatalog catalog;
    private final AgentWeaveConnectors connectors;
    private volatile boolean running;

    CatalogRefresher(CapabilityCatalog catalog, AgentWeaveConnectors connectors) {
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
