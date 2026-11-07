package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link RapClientConnector} bound to one conversation thread: its calls carry this thread's
 * {@code group_id} (and {@code thread_ancestors}, when it has ancestors). Created by
 * {@link RapClientConnector#onThread}; cheap, immutable and thread-safe.
 */
public final class RapThread implements ToolSource {

    private final RapClientConnector connector;
    private final String threadId;
    private final List<String> ancestors;

    RapThread(RapClientConnector connector, String threadId, List<String> ancestors) {
        this.connector = connector;
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.ancestors = List.copyOf(ancestors);
        if (threadId.isEmpty()) {
            throw new IllegalArgumentException("threadId must not be empty");
        }
    }

    public String threadId() {
        return threadId;
    }

    /** Ancestor thread ids, root first; empty for a root thread. */
    public List<String> ancestors() {
        return ancestors;
    }

    @Override
    public List<ToolSpec> listTools() {
        return connector.listTools();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, CallerIdentity.ANONYMOUS);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return connector.invokeOn(threadId, ancestors, invocation, caller);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
        return invoke(invocation, caller);
    }

    /** As {@link RapClientConnector#invokeAsync(ToolInvocation, CallerIdentity)}, in this thread. */
    public CompletableFuture<ToolOutcome> invokeAsync(ToolInvocation invocation, CallerIdentity caller) {
        return connector.dispatch(threadId, ancestors, invocation, caller).future();
    }

    /** Tells the tool servers this thread closed; see {@link RapClientConnector#closeThread}. */
    public CompletableFuture<Void> close() {
        return connector.closeThread(threadId);
    }
}
