package io.github.rahulbsw.protoweft.evals.discovery.llm;

import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Wraps a client and, before each call, counts the request's input tokens with an exact
 * provider counter (Anthropic {@code count_tokens}); {@link #drain()} returns the sum since the
 * last drain. Used to cross-check provider usage and the jtokkit estimate.
 */
public final class TokenCountingClient implements ModelClient {

    private final ModelClient delegate;
    private final ToLongFunction<ModelRequest> counter;
    private long counted;

    public TokenCountingClient(ModelClient delegate, ToLongFunction<ModelRequest> counter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.counter = Objects.requireNonNull(counter, "counter");
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public synchronized ModelResponse send(ModelRequest request) {
        counted += counter.applyAsLong(request);
        return delegate.send(request);
    }

    /** Exact input tokens counted since the last drain. */
    public synchronized long drain() {
        long c = counted;
        counted = 0;
        return c;
    }
}
