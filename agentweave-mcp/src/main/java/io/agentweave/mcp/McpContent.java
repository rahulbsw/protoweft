package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolSpec;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps content between MCP and the neutral core model; the decisions are recorded in SPEC.md. */
final class McpContent {

    private McpContent() {
    }

    /** One MCP part as neutral parts; an embedded resource becomes a link followed by its body. */
    static List<Content> toNeutral(McpSchema.Content part) {
        if (part instanceof McpSchema.TextContent text) {
            return List.of(new Content.Text(text.text()));
        }
        if (part instanceof McpSchema.ImageContent image) {
            return List.of(new Content.Data(image.mimeType(), image.data()));
        }
        if (part instanceof McpSchema.AudioContent audio) {
            return List.of(new Content.Data(audio.mimeType(), audio.data()));
        }
        if (part instanceof McpSchema.ResourceLink link) {
            return List.of(new Content.Link(link.uri(), link.mimeType(), link.name()));
        }
        if (part instanceof McpSchema.EmbeddedResource embedded && embedded.resource() != null) {
            return McpPromptMessage.resource(McpPromptMessage.Role.USER, toNeutral(embedded.resource())).content();
        }
        return List.of(new Content.Unmapped(part.type()));
    }

    static McpResourceContents toNeutral(McpSchema.ResourceContents contents) {
        if (contents instanceof McpSchema.BlobResourceContents blob) {
            return new McpResourceContents(blob.uri(), blob.mimeType(), null, blob.blob());
        }
        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) contents;
        return new McpResourceContents(text.uri(), text.mimeType(), text.text() == null ? "" : text.text(), null);
    }

    static McpSchema.ResourceContents toMcp(McpResourceContents contents) {
        return contents.isText() ? text(contents.uri(), contents.mimeType(), contents.text())
                : blob(contents.uri(), contents.mimeType(), contents.blob());
    }

    static McpSchema.ResourceContents text(String uri, String mimeType, String text) {
        return McpSchema.TextResourceContents.builder(uri, text).mimeType(mimeType).build();
    }

    static McpSchema.ResourceContents blob(String uri, String mimeType, String base64) {
        return McpSchema.BlobResourceContents.builder(uri, base64).mimeType(mimeType).build();
    }

    static McpPromptMessage toNeutral(McpSchema.PromptMessage message) {
        McpPromptMessage.Role role = message.role() == McpSchema.Role.ASSISTANT
                ? McpPromptMessage.Role.ASSISTANT : McpPromptMessage.Role.USER;
        return new McpPromptMessage(role, toNeutral(message.content()));
    }

    /** A prompt message carries one MCP part, or a link plus body that becomes an embedded resource. */
    static McpSchema.PromptMessage toMcp(McpPromptMessage message) {
        McpSchema.Role role = message.role() == McpPromptMessage.Role.ASSISTANT
                ? McpSchema.Role.ASSISTANT : McpSchema.Role.USER;
        List<Content> parts = message.content();
        if (parts.size() == 1) {
            return new McpSchema.PromptMessage(role, toMcp(parts.get(0)));
        }
        if (parts.size() == 2 && parts.get(0) instanceof Content.Link link) {
            if (parts.get(1) instanceof Content.Text text) {
                return new McpSchema.PromptMessage(role, McpSchema.EmbeddedResource.builder(
                        text(link.uri(), link.mimeType(), text.text())).build());
            }
            if (parts.get(1) instanceof Content.Data data) {
                return new McpSchema.PromptMessage(role, McpSchema.EmbeddedResource.builder(
                        blob(link.uri(), data.mimeType(), data.base64Data())).build());
            }
        }
        throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.MCP, "an MCP prompt message carries one "
                + "content part, or a link followed by the text or data of an embedded resource");
    }

    /** A neutral part as one MCP tool-result or prompt part. */
    static McpSchema.Content toMcp(Content part) {
        if (part instanceof Content.Text text) {
            return McpSchema.TextContent.builder(text.text()).build();
        }
        if (part instanceof Content.Data data) {
            if (data.mimeType().startsWith("image/")) {
                return McpSchema.ImageContent.builder(data.base64Data(), data.mimeType()).build();
            }
            if (data.mimeType().startsWith("audio/")) {
                return McpSchema.AudioContent.builder(data.base64Data(), data.mimeType()).build();
            }
            throw unsupported("binary content of type " + data.mimeType() + " (MCP carries only image/* "
                    + "and audio/* data inline)");
        }
        if (part instanceof Content.Json json) {
            return McpSchema.TextContent.builder(json(json.value())).build();
        }
        if (part instanceof Content.Link link) {
            return McpSchema.ResourceLink.builder()
                    .uri(link.uri())
                    .name(link.name() != null && !link.name().isEmpty() ? link.name() : link.uri())
                    .mimeType(link.mimeType())
                    .build();
        }
        throw unsupported("content of type '" + ((Content.Unmapped) part).protocolType() + "'");
    }

    /** MCP tool annotations are hints; only the ones the server set are carried over. */
    static ToolSpec toToolSpec(McpSchema.Tool t) {
        Map<String, Object> meta = new HashMap<>();
        McpSchema.ToolAnnotations a = t.annotations();
        String title = t.title() != null ? t.title() : a != null ? a.title() : null;
        if (title != null) {
            meta.put(ToolSpec.META_TITLE, title);
        }
        if (a != null) {
            putIfSet(meta, ToolSpec.META_READ_ONLY, a.readOnlyHint());
            putIfSet(meta, ToolSpec.META_DESTRUCTIVE, a.destructiveHint());
            putIfSet(meta, ToolSpec.META_IDEMPOTENT, a.idempotentHint());
            putIfSet(meta, ToolSpec.META_OPEN_WORLD, a.openWorldHint());
        }
        return new ToolSpec(t.name(), t.description(), t.inputSchema(), meta);
    }

    private static void putIfSet(Map<String, Object> meta, String key, Boolean value) {
        if (value != null) {
            meta.put(key, value);
        }
    }

    /** The prompt name or the URI template a completion refers to. */
    static String refName(McpSchema.CompleteReference ref) {
        return ref instanceof McpSchema.PromptReference p ? p.name() : ((McpSchema.ResourceReference) ref).uri();
    }

    static String json(Object value) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(value);
        } catch (IOException e) {
            throw new AgentWeaveException(ErrorCategory.INTERNAL, ProtocolId.MCP, "cannot serialize JSON content", e);
        }
    }

    private static AgentWeaveException unsupported(String what) {
        return new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.MCP, "cannot return " + what + " over MCP");
    }
}
