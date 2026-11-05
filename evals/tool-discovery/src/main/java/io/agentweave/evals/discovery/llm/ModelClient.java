package io.agentweave.evals.discovery.llm;

/**
 * A provider-neutral, single-request model client. Multi-turn behavior (tool loops) lives in
 * the arms ({@link io.agentweave.evals.discovery.arms.ToolLoop}); a client sends one request and
 * returns one reply. Implementations must replay their own {@link Turn.Assistant#providerState()}
 * when it is present and may fall back to the neutral fields when it is not (for example for
 * scripted turns).
 */
public interface ModelClient {

    /** Provider key, e.g. {@code anthropic}, {@code openai}; used for price lookup. */
    String provider();

    /** Model id as sent to the provider. */
    String model();

    /**
     * Sends one request.
     *
     * @throws ModelCallException if the provider call fails
     */
    ModelResponse send(ModelRequest request);

    /** {@code provider:model}, the key used in reports and price tables. */
    default String key() {
        return provider() + ":" + model();
    }
}
