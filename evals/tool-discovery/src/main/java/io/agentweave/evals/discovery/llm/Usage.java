package io.agentweave.evals.discovery.llm;

/**
 * Token usage of one or more model calls, normalized across providers.
 *
 * @param inputTokens      all input tokens processed, <b>including</b> cache reads and writes.
 *                         OpenAI {@code input_tokens} already includes cached tokens; for Anthropic
 *                         this is {@code input_tokens + cache_read_input_tokens +
 *                         cache_creation_input_tokens}
 * @param outputTokens     output tokens (including reasoning/thinking tokens)
 * @param cachedInputTokens input tokens served from the prompt cache (subset of {@code inputTokens})
 * @param cacheWriteTokens input tokens written to the prompt cache (subset of {@code inputTokens};
 *                         Anthropic only, 0 for OpenAI)
 */
public record Usage(long inputTokens, long outputTokens, long cachedInputTokens, long cacheWriteTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public Usage plus(Usage o) {
        return new Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cachedInputTokens + o.cachedInputTokens, cacheWriteTokens + o.cacheWriteTokens);
    }

    /** Input tokens billed at the base input rate (neither cache read nor cache write). */
    public long uncachedInputTokens() {
        return Math.max(0, inputTokens - cachedInputTokens - cacheWriteTokens);
    }
}
