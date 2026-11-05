package io.agentweave.core.agent;

import io.agentweave.core.tool.Content;

import java.util.List;

/**
 * A result produced by an agent task (a document, a dataset, an image…).
 *
 * @param artifactId identifier, or {@code null} to let the connector assign one
 * @param name       display name, or {@code null}
 * @param parts      the artifact's content
 */
public record AgentArtifact(String artifactId, String name, List<Content> parts) {

    public AgentArtifact {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public static AgentArtifact of(String name, List<Content> parts) {
        return new AgentArtifact(null, name, parts);
    }
}
