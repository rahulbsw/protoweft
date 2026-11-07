package io.github.rahulbsw.protoweft.rap;

import java.time.Instant;
import java.util.List;

/** A {@link RapStore} in memory: state lasts as long as the instance. The default. Thread-safe. */
public final class InMemoryRapStore implements RapStore {

    private final StoreState state = new StoreState();

    @Override
    public synchronized void saveCall(ClientCall call) {
        state.calls.put(call.token(), call);
    }

    @Override
    public synchronized List<ClientCall> calls() {
        return List.copyOf(state.calls.values());
    }

    @Override
    public synchronized void deleteCall(String token) {
        state.calls.remove(token);
    }

    @Override
    public synchronized boolean markProcessed(String key, Instant at) {
        return state.markProcessed(key, at);
    }

    @Override
    public synchronized boolean isProcessed(String key) {
        return state.processed.containsKey(key);
    }

    @Override
    public synchronized void purgeProcessed(Instant cutoff) {
        state.purgeProcessed(cutoff);
    }

    @Override
    public synchronized void saveSubscription(ServerSubscription subscription) {
        state.saveSubscription(subscription);
    }

    @Override
    public synchronized List<ServerSubscription> subscriptions() {
        return List.copyOf(state.subscriptions.values());
    }

    @Override
    public synchronized void deleteSubscription(String server, String threadId, String toolCallId) {
        state.subscriptions.remove(StoreState.subscriptionKey(server, threadId, toolCallId));
    }
}
