package io.agentweave.mcp;

import java.util.Objects;

/**
 * A family of MCP resources addressed by a URI template, as listed by
 * {@code resources/templates/list}.
 *
 * @param uriTemplate an RFC 6570 template with simple {@code {variable}} expressions, for example
 *                    {@code weather://{city}/today}
 * @param name        a programmatic name
 * @param title       a display name, or {@code null}
 * @param description what the resources hold, or {@code null}
 * @param mimeType    the media type of their contents, or {@code null} if unknown
 */
public record McpTemplateInfo(String uriTemplate, String name, String title, String description, String mimeType) {

    public McpTemplateInfo {
        Objects.requireNonNull(uriTemplate, "uriTemplate");
        Objects.requireNonNull(name, "name");
    }

    public static McpTemplateInfo of(String uriTemplate, String name, String mimeType) {
        return new McpTemplateInfo(uriTemplate, name, null, null, mimeType);
    }
}
