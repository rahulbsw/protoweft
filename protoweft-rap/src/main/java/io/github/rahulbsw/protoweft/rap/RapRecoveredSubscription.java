package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;

import java.util.Objects;

/**
 * A subscription confirmed before a restart and reattached from the {@link RapStore}; its events
 * keep arriving through {@link #subscription()}.
 *
 * @param call         the call that started it
 * @param subscription its events
 */
public record RapRecoveredSubscription(RapCall call, ToolSubscription subscription) {

    public RapRecoveredSubscription {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(subscription, "subscription");
    }
}
