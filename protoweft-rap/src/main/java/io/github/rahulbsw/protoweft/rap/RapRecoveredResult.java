package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.Objects;

/**
 * A tool result that arrived when no caller was waiting for it: the call was sent before a
 * restart, or it had already failed locally (for example for authorization without a
 * {@link RapPromptHandler}). Delivered to {@link RapClientConnector#onRecoveredResult}.
 *
 * @param call    the call it answers
 * @param outcome the mapped result; a subscription result carries its {@code ToolSubscription}
 */
public record RapRecoveredResult(RapCall call, ToolOutcome outcome) {

    public RapRecoveredResult {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(outcome, "outcome");
    }
}
