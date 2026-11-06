package io.github.rahulbsw.protoweft.argi.neutral;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.CapabilityCard;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import io.github.rahulbsw.protoweft.discovery.ProviderToolNames;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Catalog-based tool selection for ARGI agents: before each model call, searches a
 * {@link CapabilityCatalog} for the last user message and shows the model only the capabilities
 * found, with no extra model call. An alternative to ARGI's LLM-based tool selection.
 *
 * <pre>{@code
 * Agent agent = ReActAgent.builder(chatClient)
 *         .interceptors(List.of(CatalogToolSelectionInterceptor.of(catalog)))
 *         .build();
 * }</pre>
 *
 * <p>The found capabilities are added to the request's dynamic registry (calls go through
 * {@link CapabilityCatalog#invoke}, which re-checks permission and availability, as the caller the
 * search ran for), and the request's tool selection becomes those hits plus every tool that isn't a
 * catalog capability. ARGI's {@code ReActAgent} executes exactly the selection it showed the model.
 * The catalog's permission filter, availability filter, ranking and {@code FallbackPolicy} apply;
 * {@code NAMES_ONLY} candidates are not added (they carry no schema).
 */
public final class CatalogToolSelectionInterceptor extends ModelInterceptor {

    /** The interceptor's name ({@link #getName()}). */
    public static final String NAME = "protoweft-catalog-tool-selection";

    private final CapabilityCatalog catalog;
    private final ProviderToolNames names = new ProviderToolNames();
    private final Function<ModelRequest, CallerIdentity> caller;
    private final int limit;
    private final boolean keepOtherTools;
    private final Set<String> alwaysInclude;
    private final BiFunction<ModelRequest, DiscoveryQuery.Builder, DiscoveryQuery.Builder> queryCustomizer;
    private final BiConsumer<ModelRequest, DiscoveryResult> onResult;
    private final Executor executor;

    private CatalogToolSelectionInterceptor(Builder builder) {
        this.catalog = builder.catalog;
        this.caller = builder.caller;
        this.limit = builder.limit;
        this.keepOtherTools = builder.keepOtherTools;
        this.alwaysInclude = Set.copyOf(builder.alwaysInclude);
        this.queryCustomizer = builder.queryCustomizer;
        this.onResult = builder.onResult;
        this.executor = builder.executor;
    }

    /** An interceptor over {@code catalog} with the defaults. */
    public static CatalogToolSelectionInterceptor of(CapabilityCatalog catalog) {
        return builder(catalog).build();
    }

    public static Builder builder(CapabilityCatalog catalog) {
        return new Builder(catalog);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        CallerIdentity who = Objects.requireNonNullElse(caller.apply(request), CallerIdentity.ANONYMOUS);
        DiscoveryQuery.Builder query = DiscoveryQuery.builder(lastUserText(request.getMessages()))
                .caller(who).limit(limit);
        DiscoveryResult result = catalog.search(queryCustomizer.apply(request, query).build());
        onResult.accept(request, result);

        Set<String> catalogNames = new HashSet<>();
        for (CapabilityCard card : catalog.cards()) {
            catalogNames.add(names.providerName(card.name()));
        }
        Map<String, Tool> dynamic = new LinkedHashMap<>();
        for (Tool tool : request.getDynamicToolRegistry().tools()) {
            dynamic.put(tool.definition().name(), tool);
        }
        Map<String, String> descriptions = new HashMap<>(request.getToolDescriptions());
        Set<String> selection = new LinkedHashSet<>();
        for (DiscoveryResult.Hit hit : result.hits()) {
            String name = hit.card().name();
            ToolSpec spec = hit.schema() != null ? hit.schema() : catalog.schema(name).orElse(null);
            if (spec == null) {
                continue;
            }
            String providerName = names.providerName(name);
            ToolDefinition definition = new ToolDefinition(providerName, spec.description(),
                    Json.write(Json.objectSchema(spec.inputSchema())));
            dynamic.putIfAbsent(providerName, new ProtoweftTool(new CatalogInvoker(catalog), name, definition,
                    ctx -> who, executor));
            descriptions.putIfAbsent(providerName, spec.description());
            selection.add(providerName);
        }
        for (String name : request.getTools()) {
            if (alwaysInclude.contains(name) || (keepOtherTools && !catalogNames.contains(name))) {
                selection.add(name);
            }
        }
        ModelRequest narrowed = ModelRequest.builder(request)
                .dynamicToolRegistry(new ToolRegistry(List.copyOf(dynamic.values())))
                .toolDescriptions(descriptions)
                .tools(new ArrayList<>(selection))
                .build();
        return handler.call(narrowed);
    }

    /** The text of the last user message, or empty. */
    static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.USER) {
                return messages.get(i).text();
            }
        }
        return "";
    }

    /** The default caller: {@link ArgiNeutralTools#CALLER_KEY} in the request context, else anonymous. */
    static CallerIdentity callerFromRequest(ModelRequest request) {
        Object value = request.getContext().get(ArgiNeutralTools.CALLER_KEY);
        return value instanceof CallerIdentity identity ? identity : CallerIdentity.ANONYMOUS;
    }

    /** Invokes one catalog capability by its catalog name. */
    private record CatalogInvoker(CapabilityCatalog catalog) implements ToolSource {
        @Override
        public List<ToolSpec> listTools() {
            return List.of();
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation) {
            return invoke(invocation, CallerIdentity.ANONYMOUS);
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
            return invoke(invocation, caller, ToolProgress.NOOP);
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
            return catalog.invoke(invocation.toolName(), invocation.arguments(), caller, progress);
        }
    }

    /** Configures a {@link CatalogToolSelectionInterceptor}. */
    public static final class Builder {

        private final CapabilityCatalog catalog;
        private Function<ModelRequest, CallerIdentity> caller = CatalogToolSelectionInterceptor::callerFromRequest;
        private int limit = 5;
        private boolean keepOtherTools = true;
        private final Set<String> alwaysInclude = new LinkedHashSet<>();
        private BiFunction<ModelRequest, DiscoveryQuery.Builder, DiscoveryQuery.Builder> queryCustomizer =
                (request, query) -> query;
        private BiConsumer<ModelRequest, DiscoveryResult> onResult = (request, result) -> { };
        private Executor executor;

        private Builder(CapabilityCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        /** Derives the searching (and calling) caller from the model request. */
        public Builder callerIdentityFrom(Function<ModelRequest, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /** Hits per search before the catalog's fallback broadens (default 5). */
        public Builder limit(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be at least 1");
            }
            this.limit = limit;
            return this;
        }

        /**
         * {@code true} (the default): tools on the request that aren't catalog capabilities stay
         * selectable. {@code false}: the model sees only the hits and {@link #alwaysInclude} names.
         */
        public Builder keepOtherTools(boolean keep) {
            this.keepOtherTools = keep;
            return this;
        }

        /** Tool names (as the model sees them) that stay selectable whatever the search finds. */
        public Builder alwaysInclude(String... toolNames) {
            alwaysInclude.addAll(List.of(toolNames));
            return this;
        }

        /** Adjusts each search (filters, hints, protocols) after the defaults are applied. */
        public Builder queryCustomizer(BiFunction<ModelRequest, DiscoveryQuery.Builder, DiscoveryQuery.Builder> customizer) {
            this.queryCustomizer = Objects.requireNonNull(customizer, "customizer");
            return this;
        }

        /** Observes every search result (confidence, fallback stage, hidden counts). */
        public Builder onResult(BiConsumer<ModelRequest, DiscoveryResult> listener) {
            this.onResult = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Where catalog calls run (default: {@link ArgiNeutralTools}'s shared pool). */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public CatalogToolSelectionInterceptor build() {
            if (executor == null) {
                executor = ArgiNeutralTools.defaultExecutor();
            }
            return new CatalogToolSelectionInterceptor(this);
        }
    }
}
