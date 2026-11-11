package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;

import java.util.Objects;
import java.util.Random;

/**
 * Supplies a mocked result for a tool call in the multi-turn loop, so the loop can feed results
 * back and the token accounting can see result and history bloat. A call that matches a recorded
 * {@link ResultFixture} replays it verbatim; a miss produces a {@link SyntheticPayload} sized from
 * the fixtures' observed {@link SizeDistribution}, so an uncovered call is still realistically sized
 * rather than a token-free stub (which would make result shaping look unnecessary).
 *
 * <p>Deterministic: a miss seeds its generator from the base seed and the normalized call key, so
 * the same tool and arguments produce the same payload across runs.
 */
public final class MockResultProvider {

    private final FixtureStore store;
    private final TokenEstimator estimator;
    private final SizeDistribution sizes;
    private final long seed;

    /**
     * @param store          recorded fixtures (may be empty)
     * @param estimator      the token estimator used to size synthetic payloads
     * @param fallbackTokens target size for a miss when no fixtures were recorded
     * @param seed           base seed, so a run is reproducible
     */
    public MockResultProvider(FixtureStore store, TokenEstimator estimator, int fallbackTokens, long seed) {
        this.store = Objects.requireNonNull(store, "store");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.sizes = store.sizes(fallbackTokens);
        this.seed = seed;
    }

    /** The mocked result for {@code call}: a fixture replay, or a distribution-sized synthetic payload. */
    public ToolResult result(ToolCall call) {
        return store.lookup(call.name(), call.arguments())
                .map(f -> new ToolResult(call.id(), f.content(), false))
                .orElseGet(() -> synthetic(call));
    }

    private ToolResult synthetic(ToolCall call) {
        Random random = new Random(seed ^ NormalizedArgs.key(call.name(), call.arguments()).hashCode());
        int target = sizes.sample(random);
        return new ToolResult(call.id(), SyntheticPayload.of(call.name(), target, estimator, random), false);
    }
}
