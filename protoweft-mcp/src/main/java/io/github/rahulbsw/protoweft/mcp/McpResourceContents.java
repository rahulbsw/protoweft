package io.github.rahulbsw.protoweft.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * The contents of one MCP resource: text, or binary data carried base64-encoded.
 *
 * @param uri      the URI of the resource these contents belong to
 * @param mimeType the media type, or {@code null} if unknown
 * @param text     the text, or {@code null} for binary contents
 * @param blob     the base64-encoded bytes, or {@code null} for text contents
 */
public record McpResourceContents(String uri, String mimeType, String text, String blob) {

    public McpResourceContents {
        Objects.requireNonNull(uri, "uri");
        if ((text == null) == (blob == null)) {
            throw new IllegalArgumentException("exactly one of text and blob must be set");
        }
    }

    public static McpResourceContents text(String uri, String mimeType, String text) {
        return new McpResourceContents(uri, mimeType, Objects.requireNonNull(text, "text"), null);
    }

    public static McpResourceContents blob(String uri, String mimeType, byte[] data) {
        return new McpResourceContents(uri, mimeType, null, Base64.getEncoder().encodeToString(data));
    }

    public boolean isText() {
        return text != null;
    }

    /** The contents as bytes: decoded from base64, or the text in UTF-8. */
    public byte[] bytes() {
        return text != null ? text.getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(blob);
    }
}
