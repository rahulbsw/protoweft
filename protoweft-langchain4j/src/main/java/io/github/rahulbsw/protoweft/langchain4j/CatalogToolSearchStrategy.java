package io.github.rahulbsw.protoweft.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.LlmVisibleToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.search.ToolSearchRequest;
import dev.langchain4j.service.tool.search.ToolSearchResult;
import dev.langchain4j.service.tool.search.ToolSearchStrategy;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.CapabilityCard;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * A LangChain4j {@link ToolSearchStrategy} backed by an Protoweft {@link CapabilityCatalog}: the
 * model first sees only a {@code search_capabilities} tool, the catalog answers its searches
 * (exact filters, the caller's permissions, availability, fallback stages), and LangChain4j adds
 * the full specifications of the tools found to the following requests.
 *
 * <p>Use it together with {@link #toolProvider()}, which offers every catalog capability to the AI
 * Service as a searchable tool under the same names:
 *
 * <pre>{@code
 * CatalogToolSearchStrategy search = CatalogToolSearchStrategy.builder(catalog)
 *         .callerIdentityFrom(ctx -> callerOf(ctx))
 *         .build();
 * Assistant assistant = AiServices.builder(Assistant.class)
 *         .chatModel(model)
 *         .toolProvider(search.toolProvider())
 *         .toolSearchStrategy(search)
 *         .build();
 * }</pre>
 *
 * <p>LangChain4j records the found names in the search's {@code ToolExecutionResultMessage}
 * (attribute {@code found_tools}), so found tools stay loaded for as long as that message is in
 * chat memory. {@code ToolSearchStrategy} is {@code @Experimental} in LangChain4j. Thread-safe.
 */
public final class CatalogToolSearchStrategy implements ToolSearchStrategy {

    /** Default name of the search tool, the same as the generic meta-tool's. */
    public static final String DEFAULT_TOOL_NAME = DiscoveryTools.SEARCH;

    private static final String DEFAULT_DESCRIPTION = "Search the available tools (capabilities of tools and agents) "
            + "by what you need to do. The tools found become callable on your next turn. If confidence is LOW, "
            + "rephrase or broaden the query.";

    private final CapabilityCatalog catalog;
    private final ToolSpecification searchTool;
    private final int limit;
    private final UnaryOperator<String> nameMapper;
    private final Function<InvocationContext, CallerIdentity> caller;
    private final BiFunction<DiscoveryQuery, InvocationContext, DiscoveryQuery> queryCustomizer;
    private final ProtoweftToolProvider provider;

    private CatalogToolSearchStrategy(Builder b) {
        this.catalog = b.catalog;
        this.limit = b.limit;
        this.nameMapper = b.nameMapper;
        this.caller = b.caller;
        this.queryCustomizer = b.queryCustomizer;
        ToolSpec search = DiscoveryTools.of(catalog).listTools().stream()
                .filter(t -> t.name().equals(DiscoveryTools.SEARCH)).findFirst().orElseThrow();
        this.searchTool = ToolSpecification.builder()
                .name(b.toolName)
                .description(b.toolDescription)
                .parameters(JsonSchemas.toJsonObjectSchema(search.inputSchema()))
                .build();
        ProtoweftToolProvider.Builder provider = ProtoweftToolProvider.builder(new CatalogToolSource(catalog))
                .toolNames(nameMapper)
                .callerIdentityFrom(caller)
                .alwaysVisible(b.alwaysVisible);
        if (b.asyncExecutor != null) {
            provider.asyncExecutor(b.asyncExecutor);
        }
        this.provider = provider.build();
    }

    public static CatalogToolSearchStrategy from(CapabilityCatalog catalog) {
        return builder(catalog).build();
    }

    public static Builder builder(CapabilityCatalog catalog) {
        return new Builder(catalog);
    }

    /**
     * The AI Service's tools: every catalog capability, searchable (not sent until found) unless
     * {@linkplain Builder#alwaysVisible always visible}, named like this strategy names them.
     * Static, since LangChain4j never subjects dynamic providers' tools to search.
     */
    public ProtoweftToolProvider toolProvider() {
        return provider;
    }

    @Override
    public List<ToolSpecification> getToolSearchTools(InvocationContext invocationContext) {
        return List.of(searchTool);
    }

    @Override
    public ToolSearchResult search(ToolSearchRequest request) {
        InvocationContext context = request.invocationContext();
        DiscoveryQuery query = query(request.toolExecutionRequest().arguments(), context);
        DiscoveryResult result = catalog.search(query);

        Set<String> searchable = request.searchableTools().stream().map(ToolSpecification::name)
                .collect(Collectors.toSet());
        List<String> found = new ArrayList<>();
        List<String> alreadyLoaded = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (DiscoveryResult.Hit hit : result.hits()) {
            String llmName = nameMapper.apply(hit.card().name());
            if (searchable.contains(llmName)) {
                found.add(llmName);
                text.append(line(llmName, hit.card())).append('\n');
            } else {
                // Not searchable any more: found earlier or always visible (or not offered to this AI Service).
                alreadyLoaded.add(llmName);
            }
        }
        if (found.isEmpty() && alreadyLoaded.isEmpty()) {
            text.append("No matching tools found.\n");
        }
        if (!alreadyLoaded.isEmpty()) {
            text.append("Already available: ").append(String.join(", ", alreadyLoaded)).append('\n');
        }
        if (!result.namesOnly().isEmpty()) {
            text.append("Other candidates, not loaded (search for one by name to load it):\n");
            result.namesOnly().forEach(c -> text.append("  ").append(nameMapper.apply(c.name())).append(" - ")
                    .append(c.summary()).append('\n'));
        }
        text.append("confidence: ").append(result.confidence().level()).append("; fallback: ").append(result.stage())
                .append("; mode: ").append(result.mode());
        if (!result.hiddenUnavailable().isEmpty()) {
            // Matches hidden by permission are deliberately not mentioned: their existence must not leak.
            text.append("; hidden as unavailable: ").append(result.hiddenUnavailable());
        }
        return new ToolSearchResult(found, text.toString());
    }

    private DiscoveryQuery query(String arguments, InvocationContext context) {
        Map<String, Object> args;
        try {
            args = Json.parseObject(arguments);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new LlmVisibleToolExecutionException("arguments for " + searchTool.name() + " are not a JSON object", e);
        }
        CallerIdentity who = caller.apply(context);
        DiscoveryQuery parsed;
        try {
            parsed = DiscoveryTools.query(args, who == null ? CallerIdentity.ANONYMOUS : who);
        } catch (ProtoweftException e) {
            throw new LlmVisibleToolExecutionException(e.getMessage(), e);
        }
        if (!(args.get("limit") instanceof Number)) {
            parsed = new DiscoveryQuery(parsed.text(), limit, parsed.protocols(), parsed.versions(), parsed.sources(),
                    parsed.requireHints(), parsed.excludeHints(), parsed.caller(), parsed.includeUnavailable(),
                    parsed.inlineSchemas());
        }
        return Objects.requireNonNull(queryCustomizer.apply(parsed, context), "queryCustomizer returned null");
    }

    private static String line(String llmName, CapabilityCard card) {
        return "- " + llmName + " [" + card.protocol() + "] " + card.summary()
                + (card.parameters().isEmpty() ? "" : " (params: " + card.parameterList() + ")");
    }

    /** Configures a {@link CatalogToolSearchStrategy}. */
    public static final class Builder {

        private final CapabilityCatalog catalog;
        private String toolName = DEFAULT_TOOL_NAME;
        private String toolDescription = DEFAULT_DESCRIPTION;
        private int limit = 5;
        private UnaryOperator<String> nameMapper = ToolNames::sanitize;
        private Function<InvocationContext, CallerIdentity> caller = ctx -> CallerIdentity.ANONYMOUS;
        private BiFunction<DiscoveryQuery, InvocationContext, DiscoveryQuery> queryCustomizer = (q, ctx) -> q;
        private Predicate<ToolSpec> alwaysVisible = spec -> false;
        private Executor asyncExecutor;

        private Builder(CapabilityCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        /** Name of the search tool (default {@value #DEFAULT_TOOL_NAME}); must not clash with a capability's. */
        public Builder toolName(String toolName) {
            this.toolName = Objects.requireNonNull(toolName, "toolName");
            return this;
        }

        public Builder toolDescription(String toolDescription) {
            this.toolDescription = Objects.requireNonNull(toolDescription, "toolDescription");
            return this;
        }

        /** Hits per search when the model doesn't pass {@code limit} (default 5; fallback may broaden it). */
        public Builder limit(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be at least 1");
            }
            this.limit = limit;
            return this;
        }

        /** Maps catalog names ({@code <source>.<tool>}) to LLM tool names; default {@link ToolNames#sanitize}. */
        public Builder toolNames(UnaryOperator<String> nameMapper) {
            this.nameMapper = Objects.requireNonNull(nameMapper, "nameMapper");
            return this;
        }

        /** The caller whose scopes filter searches and who calls the capabilities. */
        public Builder callerIdentity(Supplier<CallerIdentity> caller) {
            Objects.requireNonNull(caller, "caller");
            return callerIdentityFrom(ctx -> caller.get());
        }

        /** Derives the caller from the AI Service invocation (for example its {@code InvocationParameters}). */
        public Builder callerIdentityFrom(Function<InvocationContext, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /**
         * Adjusts each query after the model's arguments are parsed, for example to restrict
         * protocols or exclude destructive capabilities for this application.
         */
        public Builder queryCustomizer(BiFunction<DiscoveryQuery, InvocationContext, DiscoveryQuery> customizer) {
            this.queryCustomizer = Objects.requireNonNull(customizer, "customizer");
            return this;
        }

        /** Capabilities sent on every request without a search (tested against the catalog spec). */
        public Builder alwaysVisible(Predicate<ToolSpec> alwaysVisible) {
            this.alwaysVisible = Objects.requireNonNull(alwaysVisible, "alwaysVisible");
            return this;
        }

        /** Executor for the capabilities in asynchronous AI Services (see {@link ProtoweftToolProvider}). */
        public Builder asyncExecutor(Executor executor) {
            this.asyncExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public CatalogToolSearchStrategy build() {
            return new CatalogToolSearchStrategy(this);
        }
    }
}
