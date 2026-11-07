package io.github.rahulbsw.protoweft.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One event delivered by a {@link ToolSubscription} after the call's first result, for example a
 * webhook notification or a line of build output.
 *
 * @param content  the event's parts
 * @param last     {@code true} if the tool declared this the final event; the subscription ends
 *                 after it
 * @param metadata protocol facts about the event (for example RAP's {@code associative} flag);
 *                 unknown keys are allowed
 */
public record ToolEvent(List<Content> content, boolean last, Map<String, Object> metadata) {

    public ToolEvent {
        content = content == null ? List.of() : List.copyOf(content);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** A non-final event with one text part and no metadata. */
    public static ToolEvent text(String text) {
        return new ToolEvent(List.of(new Content.Text(text)), false, Map.of());
    }

    /** Concatenation of all {@link Content.Text} parts, separated by newlines. */
    public String text() {
        return content.stream()
                .filter(Content.Text.class::isInstance)
                .map(c -> ((Content.Text) c).text())
                .collect(Collectors.joining("\n"));
    }
}
