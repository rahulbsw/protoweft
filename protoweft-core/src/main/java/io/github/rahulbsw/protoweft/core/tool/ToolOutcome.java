package io.github.rahulbsw.protoweft.core.tool;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The result of a tool call that reached the tool.
 *
 * @param content            ordered result parts
 * @param error              {@code true} if the tool itself reported a failure; the failure detail
 *                           is in {@code content}
 * @param structured         optional structured payload (plain maps/lists/scalars), or {@code null}
 * @param subscriptionHandle the events that follow this first result, or {@code null} for an
 *                           ordinary call; read it with {@link #subscription()}
 */
public record ToolOutcome(List<Content> content, boolean error, Object structured,
                          ToolSubscription subscriptionHandle) {

    public ToolOutcome {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** An outcome without a subscription. */
    public ToolOutcome(List<Content> content, boolean error, Object structured) {
        this(content, error, structured, null);
    }

    /** A successful outcome with one text part. */
    public static ToolOutcome text(String text) {
        return new ToolOutcome(List.of(new Content.Text(text)), false, null);
    }

    /** A tool-reported failure with one text part describing it. */
    public static ToolOutcome failure(String message) {
        return new ToolOutcome(List.of(new Content.Text(message)), true, null);
    }

    public Optional<Object> structuredContent() {
        return Optional.ofNullable(structured);
    }

    /**
     * The subscription this call started, if any: the tool keeps sending events after this
     * result. Consumers that can only use the first result ignore it.
     */
    public Optional<ToolSubscription> subscription() {
        return Optional.ofNullable(subscriptionHandle);
    }

    /** A copy carrying {@code subscription} (or none, for {@code null}). */
    public ToolOutcome withSubscription(ToolSubscription subscription) {
        return new ToolOutcome(content, error, structured, subscription);
    }

    /** Concatenation of all {@link Content.Text} parts, separated by newlines. */
    public String text() {
        return content.stream()
                .filter(Content.Text.class::isInstance)
                .map(c -> ((Content.Text) c).text())
                .collect(Collectors.joining("\n"));
    }
}
