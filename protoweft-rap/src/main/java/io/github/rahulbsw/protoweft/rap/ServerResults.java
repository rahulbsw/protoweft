package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Maps a {@link ToolOutcome} onto a {@code tool_result} message (tool-result.md). */
final class ServerResults {

    static final String ERROR_PREFIX = "Error: ";

    private ServerResults() {
    }

    /**
     * {@code text} unless the outcome has an image, then {@code content} with text and image items;
     * failures are text starting with {@code "Error: "}.
     */
    static Map<String, Object> toolResult(String groupId, String id, String callId, ToolOutcome outcome,
                                          boolean subscription) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "tool_result");
        msg.put("group_id", groupId);
        msg.put("id", id);
        if (callId != null) {
            msg.put("call_id", callId);
        }
        boolean hasImage = outcome.content().stream()
                .anyMatch(c -> c instanceof Content.Data d && d.mimeType().startsWith("image/"));
        if (outcome.error()) {
            msg.put("text", error(asText(outcome.content())));
        } else if (hasImage) {
            List<Object> items = new ArrayList<>();
            for (Content c : outcome.content()) {
                Map<String, Object> item = new LinkedHashMap<>();
                if (c instanceof Content.Data d && d.mimeType().startsWith("image/")) {
                    item.put("type", "image");
                    item.put("data", d.base64Data());
                    item.put("mediaType", d.mimeType());
                } else {
                    item.put("type", "text");
                    item.put("text", asText(List.of(c)));
                }
                items.add(item);
            }
            msg.put("content", items);
        } else if (outcome.content().isEmpty() && outcome.structured() != null) {
            msg.put("text", Json.write(outcome.structured()));
        } else {
            msg.put("text", asText(outcome.content()));
        }
        if (subscription) {
            msg.put("subscription", true);
        }
        return msg;
    }

    /** A failure message for an exception or a rejected invocation. */
    static Map<String, Object> errorResult(String groupId, String id, String callId, String message) {
        return toolResult(groupId, id, callId, ToolOutcome.failure(message), false);
    }

    static String error(String text) {
        return text.startsWith(ERROR_PREFIX) ? text : ERROR_PREFIX + (text.isBlank() ? "the tool failed" : text);
    }

    /** Text for parts RAP can only carry as text; binary bodies become markers. */
    static String asText(List<Content> content) {
        return content.stream().map(ServerResults::asText).collect(Collectors.joining("\n"));
    }

    private static String asText(Content c) {
        if (c instanceof Content.Text t) {
            return t.text();
        }
        if (c instanceof Content.Json j) {
            return Json.write(j.value());
        }
        if (c instanceof Content.Link l) {
            return l.uri();
        }
        if (c instanceof Content.Resource r) {
            return r.text() != null ? r.uri() + "\n" + r.text()
                    : "[binary resource " + r.uri() + (r.mimeType() != null ? " (" + r.mimeType() + ")" : "") + "]";
        }
        if (c instanceof Content.Data d) {
            return "[" + d.mimeType() + " data" + (d.name() != null ? " " + d.name() : "") + " omitted]";
        }
        return "[unmapped content: " + ((Content.Unmapped) c).protocolType() + "]";
    }
}
