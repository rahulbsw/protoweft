package io.github.rahulbsw.protoweft.rap;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Results nobody waits for, held until a listener is set and then delivered on the executor. */
final class RecoveredResults {

    private final ClientSettings settings;
    private final ConcurrentLinkedQueue<RapRecoveredResult> unclaimed = new ConcurrentLinkedQueue<>();
    private volatile Consumer<RapRecoveredResult> listener;

    RecoveredResults(ClientSettings settings) {
        this.settings = settings;
        this.listener = settings.recoveredListener();
    }

    void listen(Consumer<RapRecoveredResult> newListener) {
        this.listener = newListener;
        drain();
    }

    void add(RapRecoveredResult result) {
        unclaimed.add(result);
        settings.execute(this::drain);
    }

    private void drain() {
        Consumer<RapRecoveredResult> l = listener;
        RapRecoveredResult next;
        while (l != null && (next = unclaimed.poll()) != null) {
            l.accept(next);
        }
    }
}
