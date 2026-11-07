package io.github.rahulbsw.protoweft.rap;

import java.time.Duration;
import java.util.Objects;

/**
 * Retries with exponential backoff for transient HTTP failures (5xx, connection errors, timeouts),
 * as the RAP spec recommends for invocations and callbacks. 4xx responses are never retried.
 *
 * @param maxAttempts  total attempts including the first; at least 1
 * @param initialDelay delay before the first retry
 * @param maxDelay     cap on the doubling delay
 */
public record RapRetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay) {

    /** No retries. */
    public static final RapRetryPolicy NONE = new RapRetryPolicy(1, Duration.ZERO, Duration.ZERO);

    public RapRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
    }

    /** The delay before retry number {@code retry} (1-based). */
    public Duration delayBefore(int retry) {
        Duration d = initialDelay;
        for (int i = 1; i < retry && d.compareTo(maxDelay) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(maxDelay) > 0 ? maxDelay : d;
    }
}
