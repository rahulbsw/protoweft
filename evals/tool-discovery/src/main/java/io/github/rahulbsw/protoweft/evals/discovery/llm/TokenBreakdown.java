package io.github.rahulbsw.protoweft.evals.discovery.llm;

/**
 * An offline token estimate for one model request, split into the four context segments that a
 * multi-turn agent loop grows independently. Summed <b>cumulatively</b> across a loop's turns (see
 * {@link io.github.rahulbsw.protoweft.evals.discovery.arms.ToolLoop}), the split shows where tokens
 * actually go: tool definitions, tool results, and the conversation history that is re-sent every
 * turn. Because history (and every earlier tool result) is resent on each later turn, a result of
 * {@code R} tokens produced at turn {@code t} is counted again on every subsequent turn, so the
 * cumulative {@link #results()} figure surfaces the {@code R x (T - t)} compounding a per-request
 * view hides.
 *
 * <p>Estimates use jtokkit {@code o200k_base} (see {@link TokenEstimator}); provider {@code usage}
 * stays the source of truth for reported tokens, and the loop reconciles the two and reports the
 * residual.
 *
 * @param system      system prompt tokens
 * @param definitions tool-definition tokens (neutral {@code tools} + provider-specific {@code rawTools})
 * @param results     tool-result tokens (answers to tool calls, including discovery/search results)
 * @param history     user turns plus assistant text and assistant tool-call name/arguments
 */
public record TokenBreakdown(long system, long definitions, long results, long history) {

    public static final TokenBreakdown ZERO = new TokenBreakdown(0, 0, 0, 0);

    /** Total estimated input tokens of the request. */
    public long total() {
        return system + definitions + results + history;
    }

    /** Segment-wise sum, used to accumulate per-request breakdowns across a loop's turns. */
    public TokenBreakdown plus(TokenBreakdown o) {
        return new TokenBreakdown(system + o.system, definitions + o.definitions,
                results + o.results, history + o.history);
    }
}
