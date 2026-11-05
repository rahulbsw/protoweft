package io.agentweave.core.agent;

/** Lifecycle state of a unit of agent work. */
public enum TaskState {
    SUBMITTED,
    WORKING,
    /** The agent is waiting for the caller to send more input on the same task. */
    INPUT_REQUIRED,
    /** The agent needs the caller to authenticate before it can continue. */
    AUTH_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELED,
    REJECTED,
    UNKNOWN;

    /** Whether no further updates will follow. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }

    /** Whether the task is paused waiting on the caller. */
    public boolean isInterrupted() {
        return this == INPUT_REQUIRED || this == AUTH_REQUIRED;
    }
}
