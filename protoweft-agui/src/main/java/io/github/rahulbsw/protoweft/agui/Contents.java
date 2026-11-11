package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiMessage.ContentPart;
import io.github.rahulbsw.protoweft.core.tool.Content;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Maps between core {@link Content} and AG-UI content (a {@code String} or {@code List<ContentPart>}). */
public final class Contents {

    private Contents() {
    }

    /** AG-UI message or result content as core content. A provider file handle is {@link Content.Unmapped}. */
    public static List<Content> toNeutral(Object content) {
        if (content instanceof String s) {
            return List.of(new Content.Text(s));
        }
        List<Content> out = new ArrayList<>();
        if (content instanceof List<?> parts) {
            for (Object p : parts) {
                if (p instanceof ContentPart.Text t) {
                    out.add(new Content.Text(t.text()));
                } else if (p instanceof ContentPart.Media m) {
                    if (m.source() instanceof ContentPart.Source.Data d) {
                        out.add(new Content.Data(d.mimeType(), d.value(), null));
                    } else if (m.source() instanceof ContentPart.Source.Url u) {
                        out.add(new Content.Link(u.value(), u.mimeType(), null));
                    } else {
                        out.add(new Content.Unmapped("agui:" + m.kind() + "/file"));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Core content as AG-UI content: a plain {@code String} when every part is text, else parts.
     * {@link Content.Json} becomes its JSON text (the protocol has no JSON part); a text
     * {@link Content.Resource} becomes its URI and text; {@link Content.Unmapped} is dropped.
     */
    public static Object toAgUi(List<Content> content) {
        if (content.stream().allMatch(Content.Text.class::isInstance)) {
            return text(content);
        }
        List<ContentPart> parts = new ArrayList<>();
        for (Content c : content) {
            if (c instanceof Content.Text t) {
                parts.add(ContentPart.Text.of(t.text()));
            } else if (c instanceof Content.Json j) {
                parts.add(ContentPart.Text.of(Json.write(j.value())));
            } else if (c instanceof Content.Data d) {
                parts.add(new ContentPart.Media(kind(d.mimeType()),
                        new ContentPart.Source.Data(d.base64Data(), d.mimeType()), null, null));
            } else if (c instanceof Content.Link l) {
                parts.add(new ContentPart.Media(kind(l.mimeType()),
                        new ContentPart.Source.Url(l.uri(), l.mimeType()), null, null));
            } else if (c instanceof Content.Resource r) {
                if (r.text() != null) {
                    parts.add(ContentPart.Text.of(r.uri() + "\n" + r.text()));
                } else {
                    String mime = r.mimeType() == null ? "application/octet-stream" : r.mimeType();
                    parts.add(new ContentPart.Media(kind(mime), new ContentPart.Source.Data(r.base64Blob(), mime),
                            null, null));
                }
            }
            // Content.Unmapped: nothing to send, the part was never representable.
        }
        return parts;
    }

    /** The text of the parts that carry text, in order; JSON parts as JSON text. */
    public static String text(List<Content> content) {
        return content.stream()
                .map(c -> c instanceof Content.Text t ? t.text() : c instanceof Content.Json j ? Json.write(j.value()) : null)
                .filter(s -> s != null)
                .collect(Collectors.joining("\n"));
    }

    private static String kind(String mimeType) {
        if (mimeType == null) {
            return "document";
        }
        String m = mimeType.toLowerCase();
        if (m.startsWith("image/")) {
            return "image";
        }
        if (m.startsWith("audio/")) {
            return "audio";
        }
        if (m.startsWith("video/")) {
            return "video";
        }
        return "document";
    }
}
