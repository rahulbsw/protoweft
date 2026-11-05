package io.agentweave.a2a;

import io.agentweave.core.agent.AgentArtifact;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentReply;
import io.agentweave.core.agent.TaskState;
import io.agentweave.core.tool.Content;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.List;

/** Converts between AgentWeave's neutral agent model and a2a-java's spec types (see SPEC.md mapping table). */
final class A2aMapping {

    private A2aMapping() {
    }

    // ---- parts ------------------------------------------------------------------------

    static Content toContent(Part<?> part) {
        if (part instanceof TextPart text) {
            return new Content.Text(text.text());
        }
        if (part instanceof FilePart file) {
            if (file.file() instanceof FileWithBytes bytes) {
                String mime = blankToNull(bytes.mimeType());
                return new Content.Data(mime == null ? "application/octet-stream" : mime, bytes.bytes(),
                        blankToNull(bytes.name()));
            }
            if (file.file() instanceof FileWithUri uri) {
                return new Content.Link(uri.uri(), blankToNull(uri.mimeType()), blankToNull(uri.name()));
            }
        }
        if (part instanceof DataPart data) {
            return new Content.Json(data.data());
        }
        return new Content.Unmapped(part.getClass().getSimpleName());
    }

    static List<Content> toContents(List<Part<?>> parts) {
        List<Content> out = new ArrayList<>(parts == null ? 0 : parts.size());
        if (parts != null) {
            for (Part<?> p : parts) {
                out.add(toContent(p));
            }
        }
        return out;
    }

    /** @throws IllegalArgumentException for {@link Content.Unmapped}, which has no A2A form */
    static Part<?> toPart(Content content) {
        if (content instanceof Content.Text text) {
            return new TextPart(text.text());
        }
        if (content instanceof Content.Data data) {
            return new FilePart(new FileWithBytes(data.mimeType(), nullToEmpty(data.name()), data.base64Data()));
        }
        if (content instanceof Content.Resource resource) {
            // A2A has no inline resource with a URI: send the bytes as a file named after the URI's last segment.
            String base64 = resource.base64Blob() != null ? resource.base64Blob()
                    : java.util.Base64.getEncoder().encodeToString(resource.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String mime = resource.mimeType() != null ? resource.mimeType()
                    : resource.text() != null ? "text/plain" : "application/octet-stream";
            String path = java.net.URI.create(resource.uri()).getPath();
            String name = path == null || path.isEmpty() ? resource.uri() : path.substring(path.lastIndexOf('/') + 1);
            return new FilePart(new FileWithBytes(mime, name, base64));
        }
        if (content instanceof Content.Link link) {
            return new FilePart(new FileWithUri(nullToEmpty(link.mimeType()), nullToEmpty(link.name()), link.uri()));
        }
        if (content instanceof Content.Json json) {
            return new DataPart(json.value());
        }
        throw new IllegalArgumentException("content part has no A2A representation: " + content);
    }

    static List<Part<?>> toParts(List<Content> contents) {
        List<Part<?>> parts = new ArrayList<>(contents.size());
        for (Content c : contents) {
            parts.add(toPart(c));
        }
        return parts;
    }

    // ---- messages, tasks --------------------------------------------------------------

    static Message toA2a(AgentMessage message) {
        return Message.builder()
                .role(message.role() == AgentMessage.Role.USER ? Message.Role.ROLE_USER : Message.Role.ROLE_AGENT)
                .parts(toParts(message.parts()))
                .messageId(message.messageId())
                .taskId(message.taskId())
                .contextId(message.contextId())
                .metadata(message.metadata().isEmpty() ? null : message.metadata())
                .build();
    }

    static AgentMessage toNeutral(Message message) {
        if (message == null) {
            return null;
        }
        AgentMessage.Role role = message.role() == Message.Role.ROLE_USER ? AgentMessage.Role.USER : AgentMessage.Role.AGENT;
        return new AgentMessage(role, toContents(message.parts()), message.messageId(), message.taskId(),
                message.contextId(), message.metadata());
    }

    static List<AgentMessage> toNeutral(List<Message> messages) {
        List<AgentMessage> out = new ArrayList<>();
        if (messages != null) {
            for (Message m : messages) {
                out.add(toNeutral(m));
            }
        }
        return out;
    }

    static AgentArtifact toNeutral(Artifact artifact) {
        return new AgentArtifact(artifact.artifactId(), artifact.name(), toContents(artifact.parts()));
    }

    static AgentReply toReply(Task task) {
        List<AgentArtifact> artifacts = new ArrayList<>();
        if (task.artifacts() != null) {
            for (Artifact a : task.artifacts()) {
                artifacts.add(toNeutral(a));
            }
        }
        return new AgentReply(true, task.id(), task.contextId(), toNeutral(task.status().state()),
                toNeutral(task.status().message()), artifacts);
    }

    static AgentReply toReply(Message message) {
        return AgentReply.ofMessage(toNeutral(message));
    }

    static TaskState toNeutral(org.a2aproject.sdk.spec.TaskState state) {
        if (state == null) {
            return TaskState.UNKNOWN;
        }
        return switch (state) {
            case TASK_STATE_SUBMITTED -> TaskState.SUBMITTED;
            case TASK_STATE_WORKING -> TaskState.WORKING;
            case TASK_STATE_INPUT_REQUIRED -> TaskState.INPUT_REQUIRED;
            case TASK_STATE_AUTH_REQUIRED -> TaskState.AUTH_REQUIRED;
            case TASK_STATE_COMPLETED -> TaskState.COMPLETED;
            case TASK_STATE_CANCELED -> TaskState.CANCELED;
            case TASK_STATE_FAILED -> TaskState.FAILED;
            case TASK_STATE_REJECTED -> TaskState.REJECTED;
            case TASK_STATE_UNSPECIFIED -> TaskState.UNKNOWN;
        };
    }

    /** Stable lower-case, hyphenated name used in tool structured content, for example {@code input-required}. */
    static String stateName(TaskState state) {
        return state.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    // ---- descriptor <-> card ----------------------------------------------------------

    static AgentDescriptor toDescriptor(AgentCard card) {
        List<AgentDescriptor.Skill> skills = new ArrayList<>();
        for (AgentSkill s : card.skills()) {
            skills.add(new AgentDescriptor.Skill(s.id(), s.name(), s.description(), s.tags(), s.examples()));
        }
        return new AgentDescriptor(card.name(), card.description(), card.version(), skills);
    }

    static List<AgentSkill> toSkills(AgentDescriptor descriptor) {
        List<AgentSkill> skills = new ArrayList<>();
        for (AgentDescriptor.Skill s : descriptor.skills()) {
            skills.add(AgentSkill.builder().id(s.id()).name(s.name()).description(s.description())
                    .tags(s.tags()).examples(s.examples().isEmpty() ? null : s.examples()).build());
        }
        return skills;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
