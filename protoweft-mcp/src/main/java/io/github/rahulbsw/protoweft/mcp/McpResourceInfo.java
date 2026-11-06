package io.github.rahulbsw.protoweft.mcp;

import java.util.Objects;

/**
 * A resource an MCP server offers at a fixed URI, as listed by {@code resources/list}.
 *
 * @param uri         the resource URI, for example {@code file:///readme.md}
 * @param name        a programmatic name
 * @param title       a display name, or {@code null}
 * @param description what the resource holds, or {@code null}
 * @param mimeType    the media type of its contents, or {@code null} if unknown
 */
public record McpResourceInfo(String uri, String name, String title, String description, String mimeType) {

    public McpResourceInfo {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(name, "name");
    }

    public static McpResourceInfo of(String uri, String name, String mimeType) {
        return new McpResourceInfo(uri, name, null, null, mimeType);
    }
}
