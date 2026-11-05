package io.agentweave.discovery;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One conversation's discovery state: the capabilities selected so far, whose full schemas
 * stay loaded across turns without searching again. Least recently selected capabilities
 * are evicted beyond {@code maxLoaded}. Thread-safe.
 */
public final class DiscoverySession {

    public static final int DEFAULT_MAX_LOADED = 32;

    private final CapabilityCatalog catalog;
    private final CallerIdentity caller;
    private final int maxLoaded;
    private final LinkedHashMap<String, Boolean> loaded;

    public DiscoverySession(CapabilityCatalog catalog) {
        this(catalog, CallerIdentity.ANONYMOUS, DEFAULT_MAX_LOADED);
    }

    public DiscoverySession(CapabilityCatalog catalog, CallerIdentity caller, int maxLoaded) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.caller = Objects.requireNonNull(caller, "caller");
        if (maxLoaded < 1) {
            throw new IllegalArgumentException("maxLoaded must be at least 1");
        }
        this.maxLoaded = maxLoaded;
        this.loaded = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > DiscoverySession.this.maxLoaded;
            }
        };
    }

    public CapabilityCatalog catalog() {
        return catalog;
    }

    public CallerIdentity caller() {
        return caller;
    }

    /**
     * Marks capabilities as selected and returns their full schemas. Names that don't exist,
     * or that the session's caller may not see, are skipped.
     */
    public synchronized List<ToolSpec> select(List<String> names) {
        List<ToolSpec> out = new ArrayList<>();
        for (String name : names) {
            if (!catalog.visible(name, caller)) {
                continue;
            }
            catalog.schema(name).ifPresent(spec -> {
                loaded.put(name, Boolean.TRUE);
                out.add(spec);
            });
        }
        return out;
    }

    /** Selected capability names, least recently selected first. */
    public synchronized List<String> loaded() {
        return List.copyOf(loaded.keySet());
    }

    /** Current schemas of the selected capabilities (capabilities removed since are dropped). */
    public synchronized List<ToolSpec> loadedSchemas() {
        List<ToolSpec> out = new ArrayList<>();
        loaded.keySet().removeIf(name -> catalog.schema(name).isEmpty());
        for (String name : loaded.keySet()) {
            catalog.schema(name).ifPresent(out::add);
        }
        return out;
    }

    public synchronized void clear() {
        loaded.clear();
    }
}
