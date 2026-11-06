package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.Objects;

/**
 * A tool ran and reported a failure ({@link ToolOutcome#error()}). {@link ProtoweftToolCallbacks}
 * throws it as the cause of a Spring AI {@code ToolExecutionException}, so the configured
 * {@code ToolExecutionExceptionProcessor} decides what happens; Spring AI's default sends the
 * message (the outcome's text) to the model.
 */
public class ToolOutcomeException extends RuntimeException {

    private final transient ToolOutcome outcome;

    public ToolOutcomeException(String message, ToolOutcome outcome) {
        super(message);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /** The failed outcome as the tool reported it. */
    public ToolOutcome outcome() {
        return outcome;
    }
}
