package io.github.rahulbsw.protoweft.core.agent;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ProtocolConnector;

import java.util.function.Consumer;

/**
 * A connector to one remote agent. Application code should depend on this interface, not
 * on a protocol-specific class.
 */
public interface AgentConnector extends ProtocolConnector {

    /** The remote agent's self-description, available after {@link #start()}. */
    AgentDescriptor descriptor();

    /**
     * Sends a message and waits for the agent's reply. If the agent returns a task that is
     * still running, this waits until it is terminal or {@linkplain TaskState#isInterrupted()
     * interrupted} (for example waiting for input).
     *
     * @throws ProtoweftException if the message could not be delivered
     */
    AgentReply send(AgentMessage message);

    /**
     * Sends a message and streams updates to {@code events} while the agent works, returning
     * the final reply. Connectors that cannot stream deliver no events and behave like
     * {@link #send(AgentMessage)}.
     */
    AgentReply send(AgentMessage message, Consumer<AgentEvent> events);

    /** Fetches the current state of a task. */
    AgentReply getTask(String taskId);

    /** Requests cancellation of a task and returns its resulting state. */
    AgentReply cancel(String taskId);
}
