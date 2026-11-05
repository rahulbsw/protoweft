package io.agentweave.evals.discovery.llm;

/** A failed model call; {@code status} is the HTTP status when known, else -1. */
public class ModelCallException extends RuntimeException {

    private final int status;

    public ModelCallException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** A 4xx other than 408/429: retrying the same request will not help. */
    public boolean isClientError() {
        return status >= 400 && status < 500 && status != 408 && status != 429;
    }
}
