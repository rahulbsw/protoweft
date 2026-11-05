package io.agentweave.evals.discovery.cost;

import io.agentweave.evals.discovery.llm.Usage;

import java.util.Objects;
import java.util.Optional;

/**
 * Stops paid model calls before they can exceed the budget.
 *
 * <p>Before every call, {@link #beforeCall} computes a <b>worst-case</b> cost: the estimated input
 * tokens × {@code safetyFactor}, all billed at the highest input rate, plus the full output-token
 * cap. The call is refused ({@link BudgetExceededException}) when
 * <ul>
 *   <li>the global budget is 0 (the default: paid calls need an explicit {@code --budget-usd});</li>
 *   <li>{@code ledger total at start + this run's spend + worst case > global budget};</li>
 *   <li>{@code this run's spend + worst case > per-run cap};</li>
 *   <li>the model has no price and unpriced calls are not allowed, or they are allowed and the
 *       {@code maxCalls} limit is reached (without prices, the call count is the only guard).</li>
 * </ul>
 * After each call, {@link #afterCall} charges the <b>actual</b> cost from provider usage to the
 * run and to the persistent {@link SpendLedger}.
 */
public final class CostGuard {

    private final PriceTable prices;
    private final double budgetUsd;
    private final double runCapUsd;
    private final SpendLedger ledger;
    private final double safetyFactor;
    private final long maxCalls;
    private final boolean allowUnpriced;
    private final double priorUsd;
    private double runSpentUsd;
    private long runCalls;

    /**
     * @param budgetUsd     global budget across runs (ledger included); {@code <= 0} refuses all calls
     * @param runCapUsd     cap for this run; {@code <= 0} means "the remaining global budget"
     * @param safetyFactor  multiplier on estimated input tokens (estimates come from an offline tokenizer)
     * @param maxCalls      maximum calls this run; {@code <= 0} means unlimited (required when unpriced)
     * @param allowUnpriced allow calls to models without a price (then only {@code maxCalls} guards)
     */
    public CostGuard(PriceTable prices, double budgetUsd, double runCapUsd, SpendLedger ledger,
                     double safetyFactor, long maxCalls, boolean allowUnpriced) {
        this.prices = Objects.requireNonNull(prices, "prices");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.budgetUsd = budgetUsd;
        this.runCapUsd = runCapUsd;
        this.safetyFactor = Math.max(1.0, safetyFactor);
        this.maxCalls = maxCalls;
        this.allowUnpriced = allowUnpriced;
        this.priorUsd = ledger.totalUsd();
        if (allowUnpriced && maxCalls <= 0) {
            throw new IllegalArgumentException("unpriced runs need a --max-calls limit");
        }
    }

    /** Refuses the call if it could exceed a limit. */
    public synchronized void beforeCall(String modelKey, long estimatedInputTokens, long maxOutputTokens) {
        if (budgetUsd <= 0) {
            throw new BudgetExceededException("budget is $0: paid model calls are refused; set --budget-usd explicitly");
        }
        if (maxCalls > 0 && runCalls >= maxCalls) {
            throw new BudgetExceededException("call limit reached (" + maxCalls + " calls)");
        }
        Optional<PriceTable.Price> price = prices.price(modelKey);
        if (price.isEmpty()) {
            if (!allowUnpriced) {
                throw new BudgetExceededException("no price for " + modelKey
                        + "; add it with --prices/--price, or pass --allow-unpriced with --max-calls");
            }
            return;
        }
        double worst = price.get().worstCase((long) Math.ceil(estimatedInputTokens * safetyFactor), maxOutputTokens);
        if (priorUsd + runSpentUsd + worst > budgetUsd) {
            throw new BudgetExceededException(String.format(java.util.Locale.ROOT,
                    "global budget: spent $%.4f (earlier runs $%.4f + this run $%.4f), next call up to $%.4f, budget $%.2f",
                    priorUsd + runSpentUsd, priorUsd, runSpentUsd, worst, budgetUsd));
        }
        if (runCapUsd > 0 && runSpentUsd + worst > runCapUsd) {
            throw new BudgetExceededException(String.format(java.util.Locale.ROOT,
                    "per-run cap: this run spent $%.4f, next call up to $%.4f, cap $%.2f", runSpentUsd, worst, runCapUsd));
        }
    }

    /** Charges the actual cost of a finished call; returns it, or empty when the model is unpriced. */
    public synchronized Optional<Double> afterCall(String modelKey, Usage usage) {
        runCalls++;
        Optional<Double> cost = prices.price(modelKey).map(p -> p.cost(usage));
        double usd = cost.orElse(0.0);
        runSpentUsd += usd;
        ledger.record(modelKey, usd);
        return cost;
    }

    public synchronized double runSpentUsd() {
        return runSpentUsd;
    }

    public synchronized long runCalls() {
        return runCalls;
    }

    public double budgetUsd() {
        return budgetUsd;
    }

    public SpendLedger ledger() {
        return ledger;
    }

    public PriceTable prices() {
        return prices;
    }
}
