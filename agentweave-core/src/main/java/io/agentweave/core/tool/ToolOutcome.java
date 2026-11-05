package io.agentweave.core.tool;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The result of a tool call that reached the tool.
 *
 * @param content    ordered result parts
 * @param error      {@code true} if the tool itself reported a failure; the failure detail
 *                   is in {@code content}
 * @param structured optional structured payload (plain maps/lists/scalars), or {@code null}
 */
public record ToolOutcome(List<Content> content, boolean error, Object structured) {

    public ToolOutcome {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public Optional<Object> structuredContent() {
        return Optional.ofNullable(structured);
    }

    /** Concatenation of all {@link Content.Text} parts, separated by newlines. */
    public String text() {
        return content.stream()
                .filter(Content.Text.class::isInstance)
                .map(c -> ((Content.Text) c).text())
                .collect(Collectors.joining("\n"));
    }
}
