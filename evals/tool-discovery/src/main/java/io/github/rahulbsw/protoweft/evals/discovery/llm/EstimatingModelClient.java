package io.github.rahulbsw.protoweft.evals.discovery.llm;

import java.util.List;
import java.util.Objects;

/**
 * Dry-run client: makes no call, replies with no tool call, and reports the jtokkit estimate of
 * the request as its input usage (output 0). Lets {@code run-llm --dry-run} show the token and
 * worst-case cost plan of a run without keys or spend. Multi-turn arms stop after one turn.
 */
public final class EstimatingModelClient implements ModelClient {

    private final String provider;
    private final String model;
    private final TokenEstimator estimator;

    public EstimatingModelClient(String provider, String model, TokenEstimator estimator) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = Objects.requireNonNull(model, "model");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public ModelResponse send(ModelRequest request) {
        return new ModelResponse(new Turn.Assistant("(dry run)", List.of(), null),
                new Usage(estimator.request(request), 0, 0, 0), 0, "dry_run");
    }
}
