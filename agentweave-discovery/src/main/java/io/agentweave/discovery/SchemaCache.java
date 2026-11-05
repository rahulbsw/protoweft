package io.agentweave.discovery;

import io.agentweave.core.tool.ToolSpec;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full tool schemas by capability name, kept apart from the searchable cards and reused
 * across turns. An entry is replaced only when its schema hash changes.
 */
public final class SchemaCache {

    private record Entry(String hash, ToolSpec spec) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Stores {@code spec} (already named by capability) and returns whether it changed. */
    boolean put(String name, String hash, ToolSpec spec) {
        Entry previous = entries.put(name, new Entry(hash, spec));
        return previous == null || !previous.hash().equals(hash);
    }

    void remove(String name) {
        entries.remove(name);
    }

    /** The full spec for a capability, if cached. */
    public Optional<ToolSpec> get(String name) {
        Entry e = entries.get(name);
        return e == null ? Optional.empty() : Optional.of(e.spec());
    }

    /** The cached schema hash, or empty. */
    public Optional<String> hash(String name) {
        Entry e = entries.get(name);
        return e == null ? Optional.empty() : Optional.of(e.hash());
    }

    public int size() {
        return entries.size();
    }
}
