package io.github.rahulbsw.protoweft.argi.neutral;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ProgressAwareToolHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Protoweft tools in ARGI's neutral tool contract, and back.
 *
 * <pre>{@code
 * ToolRegistry tools = ArgiNeutralTools.registry(protoweftRegistry);   // MCP, UTCP, A2A, local...
 * Agent agent = ReActAgent.builder(chatClient).tools(tools).build();
 * agent.stream(new AgentRequest(messages, Map.of(ArgiNeutralTools.CALLER_KEY, caller), null), context);
 *
 * LocalTools out = LocalTools.builder().addAll(ArgiNeutralTools.toLocalTools(argiTools)).build();
 * }</pre>
 *
 * <p>Into ARGI, each tool gets a provider-safe name ({@link ToolNames#sanitize}), its schema as JSON
 * text, and an asynchronous {@code execute} that runs the Protoweft call on an executor. The caller
 * is the {@link CallerIdentity} under {@value #CALLER_KEY} in the {@link ToolContext} (ARGI's
 * {@code ReActAgent} passes the request's inputs as tool context), and a {@link ToolProgress} under
 * {@value #PROGRESS_KEY} is forwarded. Failure outcomes and {@link ProtoweftException}s become
 * {@code ToolResult}s with {@code error = true}, so the model sees the message.
 */
public final class ArgiNeutralTools {

    /** ToolContext attribute / agent input holding the Protoweft {@link CallerIdentity}. */
    public static final String CALLER_KEY = "protoweft.caller";
    /** ToolContext attribute holding a {@link ToolProgress} to forward. */
    public static final String PROGRESS_KEY = "protoweft.progress";
    /** Metadata key on tools converted out of ARGI: whether the ARGI tool was {@code returnDirect}. */
    public static final String META_RETURN_DIRECT = "argi.returnDirect";

    private static final ExecutorService DEFAULT_EXECUTOR = Executors.newCachedThreadPool(daemonThreads());

    private ArgiNeutralTools() {
    }

    /** The shared cached pool of daemon threads that Protoweft calls run on by default. */
    static Executor defaultExecutor() {
        return DEFAULT_EXECUTOR;
    }

    /** Every tool of {@code source} as an ARGI registry, with the defaults (listed once, now). */
    public static ToolRegistry registry(ToolSource source) {
        return builder(source).build();
    }

    public static Builder builder(ToolSource source) {
        return new Builder(source);
    }

    /**
     * ARGI tools as Protoweft {@link LocalTools.LocalTool}s, so Protoweft's MCP and UTCP servers
     * can serve them. Names must already match {@code [A-Za-z0-9_-]{1,64}} and be unique.
     */
    public static List<LocalTools.LocalTool> toLocalTools(Collection<? extends Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        List<LocalTools.LocalTool> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Tool tool : tools) {
            ToolDefinition definition = Objects.requireNonNull(tool, "tool").definition();
            String name = definition.name();
            if (!ToolNames.PROVIDER_SAFE.matcher(name).matches()) {
                throw new IllegalArgumentException("tool name must match " + ToolNames.PROVIDER_SAFE + ": " + name);
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
            Map<String, Object> schema;
            try {
                schema = Json.objectSchema(Json.parseObject(definition.inputSchema()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("tool '" + name + "' has an invalid input schema: " + e.getMessage(), e);
            }
            Map<String, Object> metadata = tool.returnDirect() ? Map.of(META_RETURN_DIRECT, true) : Map.of();
            ToolSpec spec = new ToolSpec(name, definition.description(), schema, metadata);
            out.add(new LocalTools.LocalTool(spec, handler(tool)));
        }
        return List.copyOf(out);
    }

    /** As {@link #toLocalTools(Collection)}, for every tool of an ARGI registry. */
    public static List<LocalTools.LocalTool> toLocalTools(ToolRegistry registry) {
        return toLocalTools(Objects.requireNonNull(registry, "registry").tools());
    }

    private static ProgressAwareToolHandler handler(Tool tool) {
        return (arguments, caller, progress) -> {
            ToolCall call = new ToolCall("protoweft-" + UUID.randomUUID(), tool.definition().name(),
                    Json.write(arguments));
            ToolContext context = new ToolContext(Map.of(CALLER_KEY, caller, PROGRESS_KEY, progress));
            CompletableFuture<ToolResult> future =
                    Objects.requireNonNull(tool.execute(call, context), "tool returned no stage").toCompletableFuture();
            ToolResult result;
            try {
                result = future.get();
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw (Error) cause;
            }
            return Results.toOutcome(Objects.requireNonNull(result, "tool returned no result"));
        };
    }

    /** The default caller: {@link #CALLER_KEY} in the tool context, else anonymous. */
    static CallerIdentity callerFromContext(ToolContext context) {
        Object value = context == null ? null : context.attributes().get(CALLER_KEY);
        return value instanceof CallerIdentity identity ? identity : CallerIdentity.ANONYMOUS;
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "protoweft-argi-tool-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Configures how a {@link ToolSource} is offered to ARGI. */
    public static final class Builder {

        private final ToolSource source;
        private Predicate<ToolSpec> filter = spec -> true;
        private UnaryOperator<String> nameMapper = ToolNames::sanitize;
        private Function<ToolContext, CallerIdentity> caller = ArgiNeutralTools::callerFromContext;
        private Executor executor = DEFAULT_EXECUTOR;

        private Builder(ToolSource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        /** Offers only the tools that match (tested against the Protoweft name and schema). */
        public Builder filter(Predicate<ToolSpec> filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /** Replaces {@link ToolNames#sanitize}; results must be unique and accepted by the model provider. */
        public Builder toolNames(UnaryOperator<String> nameMapper) {
            this.nameMapper = Objects.requireNonNull(nameMapper, "nameMapper");
            return this;
        }

        /** A fixed caller for every call, ignoring the tool context. */
        public Builder callerIdentity(Supplier<CallerIdentity> caller) {
            Objects.requireNonNull(caller, "caller");
            return callerIdentityFrom(ctx -> caller.get());
        }

        /** Derives the caller from ARGI's {@link ToolContext}, replacing the {@link #CALLER_KEY} lookup. */
        public Builder callerIdentityFrom(Function<ToolContext, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /**
         * Where calls run. The default is a shared cached pool of daemon threads, because Protoweft
         * calls block on I/O (MCP, UTCP, A2A). Cancelling ARGI's stage doesn't interrupt a running call.
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** The source's tools as ARGI {@link Tool}s, listed now. */
        public List<Tool> tools() {
            Map<String, Tool> tools = new LinkedHashMap<>();
            for (ToolSpec spec : source.listTools()) {
                if (!filter.test(spec)) {
                    continue;
                }
                String providerName = nameMapper.apply(spec.name());
                Tool existing = tools.get(providerName);
                if (existing != null) {
                    throw new IllegalStateException("tools '" + ((ProtoweftTool) existing).toolName() + "' and '"
                            + spec.name() + "' both map to the tool name '" + providerName
                            + "'; configure Builder.toolNames(...)");
                }
                ToolDefinition definition = new ToolDefinition(providerName, spec.description(),
                        Json.write(Json.objectSchema(spec.inputSchema())));
                tools.put(providerName, new ProtoweftTool(source, spec.name(), definition, caller, executor));
            }
            return List.copyOf(tools.values());
        }

        /** An ARGI registry of the source's tools, listed now (a snapshot). */
        public ToolRegistry build() {
            return new ToolRegistry(tools());
        }

        /**
         * A provider for {@code ReActAgent.builder(..).dynamicTools(..)} that lists the source again
         * each time ARGI resolves the tools (before every model turn), so tools added or removed by
         * the source (for example an MCP server's list-changed) take effect within a run.
         */
        public Function<Map<String, Object>, ToolRegistry> dynamic() {
            return state -> build();
        }
    }
}
