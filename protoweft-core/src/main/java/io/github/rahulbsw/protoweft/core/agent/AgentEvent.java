package io.github.rahulbsw.protoweft.core.agent;

/** A streamed update from a remote agent while it works on a task. */
public sealed interface AgentEvent {

    String taskId();

    /** The task changed state, optionally with a status message. */
    record StatusUpdate(String taskId, TaskState state, AgentMessage message) implements AgentEvent {
    }

    /** The task produced (part of) an artifact. */
    record ArtifactUpdate(String taskId, AgentArtifact artifact, boolean append, boolean lastChunk)
            implements AgentEvent {
    }

    /** The agent answered with a direct message instead of a task. */
    record MessageReceived(String taskId, AgentMessage message) implements AgentEvent {
    }
}
