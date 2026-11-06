package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Combines many {@link ToolSource}s (MCP servers, UTCP manuals, A2A agents, local tools)
 * into one, addressing each tool as {@code <source>.<tool>} so names never clash.
 *
 * <p>Thread-safe. Closing the registry closes every registered source that is
 * {@link AutoCloseable}.
 */
public final class ToolRegistry implements ToolSource, AutoCloseable {

    /** Separator between the source name and the tool name. */
    public static final String SEPARATOR = ".";

    private static final Pattern SOURCE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final Map<String, ToolSource> sources = new LinkedHashMap<>();

    /**
     * Adds a source under {@code name}.
     *
     * @throws IllegalArgumentException if the name is invalid or already registered
     */
    public synchronized ToolRegistry register(String name, ToolSource source) {
        Objects.requireNonNull(source, "source");
        if (name == null || !SOURCE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("source name must match " + SOURCE_NAME + ": " + name);
        }
        if (sources.containsKey(name)) {
            throw new IllegalArgumentException("source already registered: " + name);
        }
        sources.put(name, source);
        return this;
    }

    /** Removes a source without closing it. Returns the removed source, or {@code null}. */
    public synchronized ToolSource unregister(String name) {
        return sources.remove(name);
    }

    public synchronized Map<String, ToolSource> sources() {
        return Map.copyOf(sources);
    }

    @Override
    public List<ToolSpec> listTools() {
        List<ToolSpec> all = new ArrayList<>();
        for (Map.Entry<String, ToolSource> e : snapshot().entrySet()) {
            for (ToolSpec spec : e.getValue().listTools()) {
                all.add(spec.withName(e.getKey() + SEPARATOR + spec.name()));
            }
        }
        return List.copyOf(all);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, CallerIdentity.ANONYMOUS);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return invoke(invocation, caller, ToolProgress.NOOP);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
        String qualified = invocation.toolName();
        int dot = qualified.indexOf(SEPARATOR);
        ToolSource source = dot > 0 ? snapshot().get(qualified.substring(0, dot)) : null;
        if (source == null) {
            throw new ProtoweftException(ErrorCategory.TOOL_NOT_FOUND, null,
                    "no source for tool '" + qualified + "'; expected '<source>" + SEPARATOR + "<tool>'");
        }
        return source.invoke(new ToolInvocation(qualified.substring(dot + 1), invocation.arguments()), caller, progress);
    }

    @Override
    public void close() {
        RuntimeException first = null;
        for (ToolSource source : snapshot().values()) {
            if (source instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    RuntimeException wrapped = e instanceof RuntimeException re ? re : new IllegalStateException(e);
                    if (first == null) {
                        first = wrapped;
                    } else {
                        first.addSuppressed(wrapped);
                    }
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private synchronized Map<String, ToolSource> snapshot() {
        return new LinkedHashMap<>(sources);
    }
}
