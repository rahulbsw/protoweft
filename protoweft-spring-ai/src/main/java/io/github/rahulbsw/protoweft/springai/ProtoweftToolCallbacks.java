package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Offers the tools of any Protoweft {@link ToolSource} (MCP servers, UTCP manuals, A2A agents,
 * local tools, or a {@code ToolRegistry} combining them) to Spring AI as a
 * {@link ToolCallbackProvider}.
 *
 * <pre>{@code
 * ToolRegistry registry = new ToolRegistry()
 *         .register("weather", mcpConnector)
 *         .register("local", LocalTools.builder().scan(new MathTools()).build());
 *
 * String answer = chatClient.prompt()
 *         .user("What's the weather in Paris?")
 *         .tools(ProtoweftToolCallbacks.from(registry))
 *         .toolContext(Map.of(ProtoweftToolCallbacks.CALLER_KEY, caller))
 *         .call().content();
 * }</pre>
 *
 * <p>Names are made provider-safe with {@link ToolNames#sanitize} ({@code weather.forecast} is
 * offered as {@code weather__forecast}); each callback is bound to the original name. By default
 * tools are listed from the source on every {@link #getToolCallbacks()} call (Spring AI calls it
 * once per {@code ChatClient} request); caching, if wanted, belongs in the source, or turn
 * {@linkplain Builder#dynamic dynamic} listing off. Thread-safe.
 *
 * <p>Don't register an instance as a Spring bean unless the application wants it re-exposed:
 * Spring AI's MCP server auto-configuration serves every {@code ToolCallbackProvider} bean.
 */
public final class ProtoweftToolCallbacks implements ToolCallbackProvider {

    /** {@link ToolContext} key holding the {@link CallerIdentity} on whose behalf tools are called. */
    public static final String CALLER_KEY = "protoweft.caller";
    /** {@link ToolContext} key holding an {@code io.github.rahulbsw.protoweft.core.tool.ToolProgress} for the tool's progress. */
    public static final String PROGRESS_KEY = "protoweft.progress";

    private static final Map<String, Object> EMPTY_OBJECT_SCHEMA = emptyObjectSchema();

    private final ToolSource source;
    private final boolean dynamic;
    private final Predicate<ToolSpec> filter;
    private final UnaryOperator<String> nameMapper;
    private final Function<ToolContext, CallerIdentity> caller;
    private volatile Listing listing;

    private record Listing(ToolCallback[] callbacks, Map<String, String> names) {
    }

    private ProtoweftToolCallbacks(Builder b) {
        this.source = b.source;
        this.dynamic = b.dynamic;
        this.filter = b.filter;
        this.nameMapper = b.nameMapper;
        this.caller = b.caller;
    }

    /** A provider with the defaults: re-listed per call, all tools, sanitized names, caller from the tool context. */
    public static ProtoweftToolCallbacks from(ToolSource source) {
        return builder(source).build();
    }

    public static Builder builder(ToolSource source) {
        return new Builder(source);
    }

    /**
     * Lists the source's tools as Spring AI callbacks (or returns the snapshot when not
     * {@linkplain Builder#dynamic dynamic}).
     *
     * @throws IllegalStateException if two tools map to the same provider name
     * @throws io.github.rahulbsw.protoweft.core.ProtoweftException if the source cannot list its tools
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        Listing current = listing;
        if (current == null || dynamic) {
            synchronized (this) {
                current = listing;
                if (current == null || dynamic) {
                    current = list();
                    listing = current;
                }
            }
        }
        return current.callbacks().clone();
    }

    /** Drops the snapshot, so the next {@link #getToolCallbacks()} lists the source again. */
    public void refresh() {
        listing = null;
    }

    /** The Protoweft name behind a provider tool name, as of the most recent listing. */
    public Optional<String> originalName(String providerToolName) {
        Listing current = listing;
        return current == null ? Optional.empty() : Optional.ofNullable(current.names().get(providerToolName));
    }

    private Listing list() {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        for (ToolSpec spec : source.listTools()) {
            if (!filter.test(spec)) {
                continue;
            }
            String providerName = nameMapper.apply(spec.name());
            String clash = names.putIfAbsent(providerName, spec.name());
            if (clash != null) {
                throw new IllegalStateException("tools '" + clash + "' and '" + spec.name()
                        + "' both map to the provider tool name '" + providerName + "'; configure Builder.toolNames(...)");
            }
            ToolDefinition definition = ToolDefinition.builder()
                    .name(providerName)
                    .description(spec.description())
                    .inputSchema(Json.write(objectSchema(spec.inputSchema())))
                    .build();
            callbacks.put(providerName, new ProtoweftToolCallback(source, spec.name(), definition, caller));
        }
        return new Listing(callbacks.values().toArray(ToolCallback[]::new), Map.copyOf(names));
    }

    /** Providers require an object root; an empty schema or one without {@code type} becomes one. */
    static Map<String, Object> objectSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_OBJECT_SCHEMA;
        }
        if (schema.containsKey("type")) {
            return schema;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("type", "object");
        copy.putAll(schema);
        return copy;
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return java.util.Collections.unmodifiableMap(schema);
    }

    /** The default caller: {@link #CALLER_KEY} in the tool context, else anonymous. */
    static CallerIdentity callerFromContext(ToolContext context) {
        Object value = context == null ? null : context.getContext().get(CALLER_KEY);
        return value instanceof CallerIdentity identity ? identity : CallerIdentity.ANONYMOUS;
    }

    /** Configures an {@link ProtoweftToolCallbacks}. */
    public static final class Builder {

        private final ToolSource source;
        private boolean dynamic = true;
        private Predicate<ToolSpec> filter = spec -> true;
        private UnaryOperator<String> nameMapper = ToolNames::sanitize;
        private Function<ToolContext, CallerIdentity> caller = ProtoweftToolCallbacks::callerFromContext;

        private Builder(ToolSource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        /**
         * {@code true} (the default): list the source on every {@link #getToolCallbacks()} call.
         * {@code false}: list once, on first use, until {@link #refresh()}.
         */
        public Builder dynamic(boolean dynamic) {
            this.dynamic = dynamic;
            return this;
        }

        /** Offers only the tools that match (tested against the Protoweft name and schema). */
        public Builder filter(Predicate<ToolSpec> filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /** Replaces {@link ToolNames#sanitize}; the result must be unique and accepted by the model provider. */
        public Builder toolNames(UnaryOperator<String> nameMapper) {
            this.nameMapper = Objects.requireNonNull(nameMapper, "nameMapper");
            return this;
        }

        /** A fixed caller for every call, ignoring the tool context. */
        public Builder callerIdentity(Supplier<CallerIdentity> caller) {
            Objects.requireNonNull(caller, "caller");
            return callerIdentityFrom(ctx -> caller.get());
        }

        /**
         * Derives the caller from Spring AI's {@link ToolContext} (which may be {@code null}),
         * replacing the default lookup of {@link #CALLER_KEY}.
         */
        public Builder callerIdentityFrom(Function<ToolContext, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        public ProtoweftToolCallbacks build() {
            return new ProtoweftToolCallbacks(this);
        }
    }
}
