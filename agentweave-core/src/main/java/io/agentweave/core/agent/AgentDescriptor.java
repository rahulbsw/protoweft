package io.agentweave.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * A protocol-neutral self-description of an agent (published by an agent server, or read
 * from a remote agent by a client).
 *
 * @param name        display name
 * @param description what the agent does
 * @param version     the agent's own version
 * @param skills      what it can do, for discovery
 */
public record AgentDescriptor(String name, String description, String version, List<Skill> skills) {

    public AgentDescriptor {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        Objects.requireNonNull(version, "version");
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * One advertised capability.
     *
     * @param id          stable identifier
     * @param name        display name
     * @param description what the skill does
     * @param tags        keywords for discovery
     * @param examples    example prompts
     */
    public record Skill(String id, String name, String description, List<String> tags, List<String> examples) {
        public Skill {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }
}
