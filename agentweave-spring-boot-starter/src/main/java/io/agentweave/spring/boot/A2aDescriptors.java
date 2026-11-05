package io.agentweave.spring.boot;

import io.agentweave.core.agent.AgentDescriptor;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.core.env.Environment;

import java.util.List;

/** The A2A agent descriptor and fallback public URL from {@code agentweave.server.a2a.*}. */
final class A2aDescriptors {

    private A2aDescriptors() {
    }

    static AgentDescriptor fromProperties(AgentWeaveServerProperties.A2aServer a2a, Environment environment) {
        String name = a2a.getName() != null ? a2a.getName() : environment.getProperty("spring.application.name");
        if (name == null || name.isBlank()) {
            throw new InvalidConfigurationPropertyValueException("agentweave.server.a2a.name", null,
                    "set the agent name (or spring.application.name), or define an AgentDescriptor bean");
        }
        List<AgentDescriptor.Skill> skills = a2a.getSkills().stream()
                .map(s -> new AgentDescriptor.Skill(s.getId(), s.getName() != null ? s.getName() : s.getId(),
                        s.getDescription(), s.getTags(), s.getExamples()))
                .toList();
        return new AgentDescriptor(name, a2a.getDescription(), a2a.getVersion(), skills);
    }

    /** {@code http://localhost:<local port><context path>}, resolved when the card is first served. */
    static String localUrl(Environment environment) {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        return "http://localhost:" + port + (contextPath.endsWith("/") ? contextPath.substring(0, contextPath.length() - 1)
                : contextPath);
    }
}
