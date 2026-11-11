package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenBreakdown;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;

import java.util.List;
import java.util.Map;

/**
 * What one arm did for one task.
 *
 * @param arm                 arm name
 * @param taskId              task id
 * @param calls               real tool calls in order (discovery calls excluded)
 * @param discoveryCalls      calls to discovery/search tools (0 for single-turn arms)
 * @param modelCalls          model requests made
 * @param usage               summed provider usage over all model calls
 * @param latencyMillis       summed API latency
 * @param toolsOffered        tool definitions in the first request
 * @param toolPayloadTokens   jtokkit o200k estimate of tool-definition tokens, summed over requests
 *                            (equals {@code estimated.definitions()})
 * @param estimated           the cumulative jtokkit input estimate over every request, split
 *                            system/definitions/results/history; reconcile against
 *                            {@code usage.inputTokens}
 * @param perRequest          one {@link ToolLoop.RequestTokens} per model call, for per-request
 *                            reconciliation (per-call definitions stay flat; the residual isolates
 *                            provider framing/tokenizer overhead from cumulative re-send effects)
 * @param finalText           the model's last text output (for no-call diagnostics)
 * @param hitMaxTurns         the tool loop stopped at its turn limit
 * @param error               model-call error, or {@code null}
 * @param extras              arm-specific facts for the per-call log (e.g. retrieved ids)
 */
public record ArmResult(String arm, String taskId, List<SelectedCall> calls, int discoveryCalls, int modelCalls,
                        Usage usage, long latencyMillis, int toolsOffered, long toolPayloadTokens,
                        TokenBreakdown estimated, List<ToolLoop.RequestTokens> perRequest, String finalText,
                        boolean hitMaxTurns, String error, Map<String, Object> extras) {

    public ArmResult {
        calls = calls == null ? List.of() : List.copyOf(calls);
        usage = usage == null ? Usage.ZERO : usage;
        estimated = estimated == null ? TokenBreakdown.ZERO : estimated;
        perRequest = perRequest == null ? List.of() : List.copyOf(perRequest);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /** Provider input tokens minus the cumulative jtokkit estimate (framing the estimate omits). */
    public long reconciliationResidual() {
        return usage.inputTokens() - estimated.total();
    }

    /** Tool-definition tokens for a single request (the first call), not re-summed across turns. */
    public long definitionTokensPerCall() {
        return perRequest.isEmpty() ? estimated.definitions() : perRequest.get(0).estimate().definitions();
    }

    /** Mean per-request residual (provider minus estimate), averaged over the model calls made. */
    public long reconciliationResidualPerCall() {
        return perRequest.isEmpty() ? 0 : Math.round((double) reconciliationResidual() / perRequest.size());
    }
}
