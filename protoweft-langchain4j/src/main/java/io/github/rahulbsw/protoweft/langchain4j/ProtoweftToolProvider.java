package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Offers the tools of any Protoweft {@link ToolSource} (MCP servers, UTCP manuals, A2A agents,
 * local tools, or a {@code ToolRegistry} combining them) to a LangChain4j AI Service.
 *
 * <pre>{@code
 * ToolRegistry registry = new ToolRegistry()
 *         .register("weather", mcpConnector)
 *         .register("local", LocalTools.builder().scan(new MathTools()).build());
 *
 * Assistant assistant = AiServices.builder(Assistant.class)
 *         .chatModel(model)
 *         .toolProvider(ProtoweftToolProvider.from(registry))
 *         .build();
 * }</pre>
 *
 * <p>Tools are listed from the source on every {@link #provideTools} call (once per AI Service
 * invocation, or before every model call when {@linkplain Builder#dynamic dynamic}); caching, if
 * wanted, belongs in the source. Names are made provider-safe with {@link ToolNames#sanitize}
 * (so {@code weather.forecast} is offered as {@code weather__forecast}); each executor is bound to
 * the original name. Thread-safe.
 */
public final class ProtoweftToolProvider implements ToolProvider {

    /** {@link ToolSpecification#metadata()} key holding the original Protoweft tool name. */
    public static final String TOOL_NAME_METADATA = "protoweft.toolName";

    private static final Executor DEFAULT_ASYNC_EXECUTOR = defaultAsyncExecutor();

    private final ToolSource source;
    private final boolean dynamic;
    private final Predicate<ToolSpec> filter;
    private final Predicate<ToolSpec> alwaysVisible;
    private final UnaryOperator<String> nameMapper;
    private final Function<InvocationContext, CallerIdentity> caller;
    private final Executor asyncExecutor;
    private volatile Map<String, String> lastNames = Map.of();

    private ProtoweftToolProvider(Builder b) {
        this.source = b.source;
        this.dynamic = b.dynamic;
        this.filter = b.filter;
        this.alwaysVisible = b.alwaysVisible;
        this.nameMapper = b.nameMapper;
        this.caller = b.caller;
        this.asyncExecutor = b.asyncExecutor;
    }

    /** A provider with the defaults: static, all tools, sanitized names, anonymous caller. */
    public static ProtoweftToolProvider from(ToolSource source) {
        return builder(source).build();
    }

    public static Builder builder(ToolSource source) {
        return new Builder(source);
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult.Builder result = ToolProviderResult.builder();
        Map<String, String> names = new HashMap<>();
        for (ToolSpec spec : source.listTools()) {
            if (!filter.test(spec)) {
                continue;
            }
            String llmName = nameMapper.apply(spec.name());
            String clash = names.putIfAbsent(llmName, spec.name());
            if (clash != null) {
                throw new IllegalStateException("tools '" + clash + "' and '" + spec.name()
                        + "' both map to the LLM tool name '" + llmName + "'; configure Builder.toolNames(...)");
            }
            ToolSpecification specification;
            try {
                ToolSpecification.Builder b = ToolSpecification.builder()
                        .name(llmName)
                        .description(spec.description().isEmpty() ? null : spec.description())
                        .parameters(JsonSchemas.toJsonObjectSchema(spec.inputSchema()))
                        .addMetadata(TOOL_NAME_METADATA, spec.name());
                if (alwaysVisible.test(spec)) {
                    b.addMetadata(ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.ALWAYS_VISIBLE);
                }
                specification = b.build();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("tool '" + spec.name() + "': " + e.getMessage(), e);
            }
            result.add(specification, new ProtoweftToolExecutor(source, spec.name(), caller, asyncExecutor));
        }
        lastNames = Map.copyOf(names);
        return result.build();
    }

    @Override
    public boolean isDynamic() {
        return dynamic;
    }

    /** The Protoweft name behind an LLM tool name, as of the most recent {@link #provideTools} call. */
    public Optional<String> originalName(String llmToolName) {
        return Optional.ofNullable(lastNames.get(llmToolName));
    }

    /** Configures an {@link ProtoweftToolProvider}. */
    public static final class Builder {

        private final ToolSource source;
        private boolean dynamic;
        private Predicate<ToolSpec> filter = spec -> true;
        private Predicate<ToolSpec> alwaysVisible = spec -> false;
        private UnaryOperator<String> nameMapper = ToolNames::sanitize;
        private Function<InvocationContext, CallerIdentity> caller = ctx -> CallerIdentity.ANONYMOUS;
        private Executor asyncExecutor = DEFAULT_ASYNC_EXECUTOR;

        private Builder(ToolSource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        /** Re-list tools before every model call in the tool loop, not just once per invocation. */
        public Builder dynamic(boolean dynamic) {
            this.dynamic = dynamic;
            return this;
        }

        /** Offers only the tools that match (tested against the Protoweft name and schema). */
        public Builder filter(Predicate<ToolSpec> filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Marks the matching tools {@link SearchBehavior#ALWAYS_VISIBLE}: with a LangChain4j
         * {@code ToolSearchStrategy} configured, they are sent on every request instead of only
         * after a tool search found them (the provider-side equivalent of
         * {@code @Tool(searchBehavior = ALWAYS_VISIBLE)}). Other tools stay
         * {@link SearchBehavior#SEARCHABLE}, LangChain4j's default. No effect without a strategy.
         */
        public Builder alwaysVisible(Predicate<ToolSpec> alwaysVisible) {
            this.alwaysVisible = Objects.requireNonNull(alwaysVisible, "alwaysVisible");
            return this;
        }

        /** Replaces {@link ToolNames#sanitize}; the result must be unique and accepted by the LLM provider. */
        public Builder toolNames(UnaryOperator<String> nameMapper) {
            this.nameMapper = Objects.requireNonNull(nameMapper, "nameMapper");
            return this;
        }

        /** The caller passed to {@link ToolSource#invoke(io.github.rahulbsw.protoweft.core.tool.ToolInvocation, CallerIdentity)}. */
        public Builder callerIdentity(Supplier<CallerIdentity> caller) {
            Objects.requireNonNull(caller, "caller");
            return callerIdentityFrom(ctx -> caller.get());
        }

        /**
         * Derives the caller from the AI Service invocation, for example from its
         * {@code InvocationParameters} or chat memory id. The context may be {@code null}.
         */
        public Builder callerIdentityFrom(Function<InvocationContext, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /** Runs tools for asynchronous AI Services; the default is a shared cached pool of daemon threads. */
        public Builder asyncExecutor(Executor executor) {
            this.asyncExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public ProtoweftToolProvider build() {
            return new ProtoweftToolProvider(this);
        }
    }

    private static Executor defaultAsyncExecutor() {
        AtomicInteger count = new AtomicInteger();
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "protoweft-langchain4j-tool-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        return pool;
    }
}
