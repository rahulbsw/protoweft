package io.github.rahulbsw.protoweft.evals.discovery.llm;

import java.util.List;
import java.util.Objects;

/** One entry of a provider-neutral conversation. */
public sealed interface Turn permits Turn.User, Turn.Assistant, Turn.ToolResults {

    /** A user message. */
    record User(String text) implements Turn {
        public User {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A model reply.
     *
     * @param text          concatenated text output (may be empty)
     * @param toolCalls     tool calls in output order
     * @param providerState the provider's own representation of this reply (for example the
     *                      Anthropic {@code Message} or the OpenAI output items), replayed verbatim
     *                      by the same {@link ModelClient} on later turns so thinking/reasoning
     *                      items survive; opaque to everyone else, may be {@code null}
     */
    record Assistant(String text, List<ToolCall> toolCalls, Object providerState) implements Turn {
        public Assistant {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** Answers to the tool calls of the preceding assistant turn. */
    record ToolResults(List<ToolResult> results) implements Turn {
        public ToolResults {
            results = List.copyOf(results);
        }
    }
}
