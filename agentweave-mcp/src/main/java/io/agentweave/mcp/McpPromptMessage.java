package io.agentweave.mcp;

import io.agentweave.core.tool.Content;

import java.util.List;
import java.util.Objects;

/**
 * One message of an MCP prompt, in protocol-neutral content.
 *
 * <p>MCP puts exactly one content part in a prompt message. Text, image/audio data and resource
 * links map to one {@link Content} part each. An embedded resource maps to two parts: a
 * {@link Content.Link} naming the resource, followed by its body as {@link Content.Text} or
 * {@link Content.Data}; {@link #resource(Role, McpResourceContents)} builds that pair.
 *
 * @param role    who speaks the message
 * @param content the message content
 */
public record McpPromptMessage(Role role, List<Content> content) {

    /** The speaker of a prompt message. */
    public enum Role { USER, ASSISTANT }

    public McpPromptMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("a prompt message needs content");
        }
    }

    public static McpPromptMessage user(String text) {
        return new McpPromptMessage(Role.USER, List.of(new Content.Text(text)));
    }

    public static McpPromptMessage assistant(String text) {
        return new McpPromptMessage(Role.ASSISTANT, List.of(new Content.Text(text)));
    }

    /** An image (or audio clip), given base64-encoded. */
    public static McpPromptMessage data(Role role, String mimeType, String base64Data) {
        return new McpPromptMessage(role, List.of(new Content.Data(mimeType, base64Data)));
    }

    /** An embedded resource: a link to it followed by its contents. */
    public static McpPromptMessage resource(Role role, McpResourceContents resource) {
        Content body = resource.isText() ? new Content.Text(resource.text())
                : new Content.Data(resource.mimeType() == null ? "application/octet-stream" : resource.mimeType(),
                        resource.blob());
        return new McpPromptMessage(role, List.of(new Content.Link(resource.uri(), resource.mimeType(), null), body));
    }

    /** The text of a single-part text message, or {@code null}. */
    public String text() {
        return content.size() == 1 && content.get(0) instanceof Content.Text t ? t.text() : null;
    }
}
