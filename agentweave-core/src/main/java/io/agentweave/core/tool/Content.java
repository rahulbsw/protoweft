package io.agentweave.core.tool;

import java.util.Objects;

/**
 * One part of a tool result, in protocol-neutral form.
 *
 * <p>Content a connector cannot faithfully map is surfaced as {@link Unmapped} rather than
 * dropped or approximated, so callers can see exactly what was lost.
 */
public sealed interface Content {

    /** Plain text. */
    record Text(String text) implements Content {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Binary data such as an image, an audio clip or a file.
     *
     * @param mimeType   the media type, for example {@code image/png}
     * @param base64Data the payload, base64-encoded as received on the wire
     * @param name       a file name, or {@code null}
     */
    record Data(String mimeType, String base64Data, String name) implements Content {
        public Data {
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(base64Data, "base64Data");
        }

        /** Data without a file name. */
        public Data(String mimeType, String base64Data) {
            this(mimeType, base64Data, null);
        }
    }

    /**
     * A resource delivered inline together with its identity (for example an MCP embedded
     * resource): its URI plus its contents, as text or as base64 bytes. Exactly one of
     * {@code text} and {@code base64Blob} is set.
     *
     * @param uri        the resource's URI
     * @param mimeType   the media type, or {@code null}
     * @param text       the contents as text, or {@code null}
     * @param base64Blob the contents as base64 bytes, or {@code null}
     */
    record Resource(String uri, String mimeType, String text, String base64Blob) implements Content {
        public Resource {
            Objects.requireNonNull(uri, "uri");
            if ((text == null) == (base64Blob == null)) {
                throw new IllegalArgumentException("exactly one of text and base64Blob must be set");
            }
        }

        public static Resource text(String uri, String mimeType, String text) {
            return new Resource(uri, mimeType, text, null);
        }

        public static Resource blob(String uri, String mimeType, String base64Blob) {
            return new Resource(uri, mimeType, null, base64Blob);
        }
    }

    /**
     * Structured data, as plain maps/lists/scalars (for example an A2A data part).
     */
    record Json(Object value) implements Content {
    }

    /**
     * A reference to content by URI rather than inline bytes (for example an MCP resource
     * link or an A2A file part with a URI).
     *
     * @param uri      where the content lives
     * @param mimeType the media type, or {@code null} if unknown
     * @param name     a display name, or {@code null}
     */
    record Link(String uri, String mimeType, String name) implements Content {
        public Link {
            Objects.requireNonNull(uri, "uri");
        }
    }

    /**
     * A content part this connector does not map yet.
     *
     * @param protocolType the protocol's own type tag, for example {@code "resource_link"}
     */
    record Unmapped(String protocolType) implements Content {
        public Unmapped {
            Objects.requireNonNull(protocolType, "protocolType");
        }
    }
}
