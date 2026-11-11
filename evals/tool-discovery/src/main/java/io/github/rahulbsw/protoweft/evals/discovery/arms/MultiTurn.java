package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.fixtures.MockResultProvider;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.HistoryPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.ResultPolicy;

import java.util.Objects;

/**
 * The run-wide multi-turn context passed to arms when {@code --multiturn} is set: one shared
 * {@link MockResultProvider} and a turn cap. The result and history shaping policies are per-arm
 * (the study's factorial axes), so the arm passes its own policies to {@link #mode}. {@code null}
 * means single-turn.
 *
 * <p>Currently applied by the provider-neutral baseline arms ({@code all-tools}, {@code top-k});
 * discovery and native arms stay single-turn until the baseline-run milestone enables them.
 */
public record MultiTurn(MockResultProvider provider, int maxTurns) {

    public MultiTurn {
        Objects.requireNonNull(provider, "provider");
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1");
        }
    }

    /** The task's gold-call count (its reference trajectory), at least 1. */
    public int goldCalls(EvalTask task) {
        int gold = task.goldCalls().isEmpty() ? task.goldToolIds().size() : task.goldCalls().size();
        return Math.max(1, gold);
    }

    /** Turns to allow a task: enough for its trajectory plus a closing no-call turn. */
    public int turnsFor(EvalTask task) {
        return Math.max(maxTurns, goldCalls(task) + 1);
    }

    /** The mock mode for a task with the arm's shaping policies. */
    public ToolLoop.MockMode mode(EvalTask task, ResultPolicy resultPolicy, HistoryPolicy historyPolicy) {
        return new ToolLoop.MockMode(provider, goldCalls(task), resultPolicy, historyPolicy);
    }
}
