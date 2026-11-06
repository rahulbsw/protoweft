package io.github.rahulbsw.protoweft.evals.discovery.cost;

import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelRequest;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelResponse;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;

import java.util.Objects;

/**
 * Wraps a {@link ModelClient} so every call passes the {@link CostGuard} first and is charged
 * afterwards. Arms never see the guard; a refused call surfaces as
 * {@link BudgetExceededException}, which the runner turns into a clean, partial report.
 */
public final class GuardedModelClient implements ModelClient {

    private final ModelClient delegate;
    private final CostGuard guard;
    private final TokenEstimator estimator;

    public GuardedModelClient(ModelClient delegate, CostGuard guard, TokenEstimator estimator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
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
    public ModelResponse send(ModelRequest request) {
        guard.beforeCall(key(), estimator.request(request), request.maxOutputTokens());
        ModelResponse response = delegate.send(request);
        guard.afterCall(key(), response.usage());
        return response;
    }

    public ModelClient delegate() {
        return delegate;
    }
}
