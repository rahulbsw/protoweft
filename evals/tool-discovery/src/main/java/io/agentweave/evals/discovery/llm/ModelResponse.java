package io.agentweave.evals.discovery.llm;

import java.util.Objects;

/**
 * The model's reply to one {@link ModelRequest}.
 *
 * @param turn          the reply, to be appended to the conversation as-is
 * @param usage         provider-reported usage (the source of truth for tokens and cost)
 * @param latencyMillis wall-clock time of the API call
 * @param stopReason    provider stop reason / status, for diagnostics
 */
public record ModelResponse(Turn.Assistant turn, Usage usage, long latencyMillis, String stopReason) {

    public ModelResponse {
        Objects.requireNonNull(turn, "turn");
        usage = usage == null ? Usage.ZERO : usage;
    }
}
