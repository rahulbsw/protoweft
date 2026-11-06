package io.github.rahulbsw.protoweft.evals.discovery.arms;

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
 * @param finalText           the model's last text output (for no-call diagnostics)
 * @param hitMaxTurns         the tool loop stopped at its turn limit
 * @param error               model-call error, or {@code null}
 * @param extras              arm-specific facts for the per-call log (e.g. retrieved ids)
 */
public record ArmResult(String arm, String taskId, List<SelectedCall> calls, int discoveryCalls, int modelCalls,
                        Usage usage, long latencyMillis, int toolsOffered, long toolPayloadTokens, String finalText,
                        boolean hitMaxTurns, String error, Map<String, Object> extras) {

    public ArmResult {
        calls = calls == null ? List.of() : List.copyOf(calls);
        usage = usage == null ? Usage.ZERO : usage;
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
