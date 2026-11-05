package io.agentweave.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prompts an {@link McpToolServer} publishes, with completers for their arguments. Hand it to
 * {@code McpToolServer.http(tools).prompts(...)} (or {@code stdio}).
 *
 * <pre>{@code
 * McpPrompts prompts = McpPrompts.builder()
 *         .prompt(McpPromptInfo.of("review", "Reviews code", McpPromptArgument.required("code", "The code")),
 *                 req -> "Review this code:\n" + req.argument("code"))
 *         .completion("review", "language", McpCompleter.of(List.of("java", "kotlin")))
 *         .scan(new ReviewPrompts())                       // @McpPrompt methods
 *         .build();
 * }</pre>
 *
 * <p>The set is fixed once built; handlers and completers must be thread-safe.
 */
public final class McpPrompts {

    record Entry(McpPromptInfo info, McpPromptHandler handler) {
    }

    private final List<Entry> entries;
    private final Map<String, Map<String, McpCompleter>> completers;

    private McpPrompts(Builder b) {
        this.entries = List.copyOf(b.entries.values());
        Map<String, Map<String, McpCompleter>> copy = new LinkedHashMap<>();
        b.completers.forEach((prompt, byArgument) -> copy.put(prompt, Map.copyOf(byArgument)));
        this.completers = Map.copyOf(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Prompts from the annotated methods of {@code targets} only. */
    public static McpPrompts scan(Object... targets) {
        return builder().scan(targets).build();
    }

    /** The prompts, in registration order. */
    public List<McpPromptInfo> prompts() {
        return entries.stream().map(Entry::info).toList();
    }

    List<Entry> entries() {
        return entries;
    }

    Map<String, Map<String, McpCompleter>> completers() {
        return completers;
    }

    /** Builds {@link McpPrompts}. */
    public static final class Builder {

        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final Map<String, Map<String, McpCompleter>> completers = new LinkedHashMap<>();

        private Builder() {
        }

        /** Adds a prompt. */
        public Builder prompt(McpPromptInfo info, McpPromptHandler handler) {
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(handler, "handler");
            if (entries.putIfAbsent(info.name(), new Entry(info, handler)) != null) {
                throw new IllegalArgumentException("duplicate prompt name: " + info.name());
            }
            return this;
        }

        /** Adds a prompt without arguments. */
        public Builder prompt(String name, String description, McpPromptHandler handler) {
            return prompt(McpPromptInfo.of(name, description), handler);
        }

        /** Completes {@code argument} of the prompt {@code prompt}. */
        public Builder completion(String prompt, String argument, McpCompleter completer) {
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(argument, "argument");
            Objects.requireNonNull(completer, "completer");
            if (completers.computeIfAbsent(prompt, k -> new LinkedHashMap<>()).putIfAbsent(argument, completer)
                    != null) {
                throw new IllegalArgumentException("duplicate completer for prompt " + prompt + " argument "
                        + argument);
            }
            return this;
        }

        /**
         * Adds every public {@link McpPrompt} and {@link McpComplete}{@code (prompt = ...)}
         * method of each object.
         *
         * @throws IllegalArgumentException if a method has an unsupported signature
         */
        public Builder scan(Object... targets) {
            for (Object target : targets) {
                AnnotationScanner.scanPrompts(Objects.requireNonNull(target, "target"), this);
            }
            return this;
        }

        /**
         * @throws IllegalArgumentException if a completer names an unknown prompt or argument
         */
        public McpPrompts build() {
            completers.forEach((prompt, byArgument) -> {
                Entry entry = entries.get(prompt);
                if (entry == null) {
                    throw new IllegalArgumentException("completer for unknown prompt: " + prompt);
                }
                for (String argument : byArgument.keySet()) {
                    if (entry.info().arguments().stream().noneMatch(a -> a.name().equals(argument))) {
                        throw new IllegalArgumentException("prompt " + prompt + " has no argument " + argument);
                    }
                }
            });
            return new McpPrompts(this);
        }
    }
}
