package io.agentweave.argi.neutral;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolOutcome;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.tool.ToolResult;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Converts results between AgentWeave's {@link ToolOutcome} and ARGI's {@link ToolResult}. */
final class Results {

    /** ToolResult metadata key carrying the {@link io.agentweave.core.ErrorCategory} of a failure. */
    static final String ERROR_CATEGORY = "agentweave.errorCategory";

    private static final String OCTET_STREAM = "application/octet-stream";

    private Results() {
    }

    /**
     * AgentWeave outcome to ARGI result: text stays text, JSON becomes a JSON part, binary data
     * becomes an image/audio/other binary part, links become their URI and unmapped parts a visible
     * marker. With no parts, a structured payload becomes one JSON part.
     */
    static ToolResult toResult(ToolOutcome outcome) {
        List<ContentPart> parts = new ArrayList<>();
        for (Content content : outcome.content()) {
            parts.add(toPart(content));
        }
        if (parts.isEmpty() && outcome.structured() != null) {
            parts.add(ContentPart.json(Json.write(outcome.structured())));
        }
        return new ToolResult(parts, outcome.error(), Map.of());
    }

    /** A failure the model sees, tagged with its category. */
    static ToolResult failure(AgentWeaveException e) {
        String message = e.getMessage() == null ? e.category().name() : e.getMessage();
        return new ToolResult(List.of(ContentPart.text(message)), true, Map.of(ERROR_CATEGORY, e.category().name()));
    }

    static ContentPart toPart(Content content) {
        if (content instanceof Content.Text t) {
            return ContentPart.text(t.text());
        }
        if (content instanceof Content.Json j) {
            return ContentPart.json(Json.write(j.value()));
        }
        if (content instanceof Content.Data d) {
            return ContentPart.binary(binaryType(d.mimeType()), d.mimeType(), Base64.getDecoder().decode(d.base64Data()));
        }
        if (content instanceof Content.Link l) {
            return ContentPart.text(l.uri());
        }
        if (content instanceof Content.Resource r) {
            String mime = r.mimeType() != null ? r.mimeType() : "application/octet-stream";
            return r.text() != null ? ContentPart.text(r.text())
                    : ContentPart.binary(binaryType(mime), mime, Base64.getDecoder().decode(r.base64Blob()));
        }
        return ContentPart.text("[unsupported content: " + ((Content.Unmapped) content).protocolType() + "]");
    }

    private static ContentPart.Type binaryType(String mimeType) {
        if (mimeType.startsWith("image/")) {
            return ContentPart.Type.IMAGE;
        }
        if (mimeType.startsWith("audio/")) {
            return ContentPart.Type.AUDIO;
        }
        return ContentPart.Type.OTHER;
    }

    /**
     * ARGI result to AgentWeave outcome: text and JSON parts keep their kind (JSON parsed to plain
     * values, or kept as text if it doesn't parse), binary parts become base64 data, and a result
     * whose only part is JSON also carries it as the structured payload.
     */
    static ToolOutcome toOutcome(ToolResult result) {
        List<Content> content = new ArrayList<>();
        Object structured = null;
        for (ContentPart part : result.content()) {
            switch (part.type()) {
                case TEXT -> content.add(new Content.Text(part.text() == null ? "" : part.text()));
                case JSON -> {
                    Object value = Json.parseOr(part.text(), null);
                    content.add(value == null ? new Content.Text(part.text() == null ? "" : part.text())
                            : new Content.Json(value));
                    if (result.content().size() == 1) {
                        structured = value;
                    }
                }
                default -> content.add(binary(part));
            }
        }
        return new ToolOutcome(content, result.error(), structured);
    }

    private static Content binary(ContentPart part) {
        byte[] data = part.data();
        if (data == null) {
            return part.text() != null ? new Content.Text(part.text()) : new Content.Unmapped("argi:" + part.type());
        }
        String mime = part.mimeType() == null || part.mimeType().isBlank() ? OCTET_STREAM : part.mimeType();
        return new Content.Data(mime, Base64.getEncoder().encodeToString(data));
    }

    /** Text of a result for diagnostics: text and JSON parts joined. */
    static String text(ToolResult result) {
        StringBuilder out = new StringBuilder();
        for (ContentPart part : result.content()) {
            if (part.text() != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(part.text());
            }
        }
        return out.toString();
    }
}
