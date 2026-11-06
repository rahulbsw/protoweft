package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.discovery.CapabilityCard;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * An Protoweft {@link CapabilityCatalog} as Spring AI's tool-search index, for
 * {@code ToolSearchToolCallingAdvisor} (module {@code spring-ai-tool-search-advisor}).
 *
 * <pre>{@code
 * CatalogToolIndex index = CatalogToolIndex.builder(catalog)
 *         .callerIdentityFrom(conversationId -> callerOf(conversationId))
 *         .build();
 * ChatClient chat = ChatClient.builder(model)
 *         .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).build())
 *         .defaultTools(index.toolCallbacks())        // every capability, same names as the index
 *         .build();
 * chat.prompt().user("...")
 *         .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
 *         .call().content();
 * }</pre>
 *
 * <p>The catalog is the index: {@link #indexTool}, {@link #indexTools} and {@link #clearIndex}
 * do nothing, and {@link #search} runs {@link CapabilityCatalog#search}, so the catalog's
 * permission filter, availability filter, ranking and fallback all apply. Hits are named with the
 * same mapper as {@link #toolCallbacks()}, so the advisor finds each hit among the registered
 * callbacks. Only catalog capabilities are searchable; other callbacks on the request are never
 * found (add them to the catalog, for example as a {@code LocalTools} source). Thread-safe.
 */
public final class CatalogToolIndex implements ToolIndex {

    /** {@code SearchMetadata.searchType} of every response. */
    public static final String SEARCH_TYPE = "CatalogToolIndex";

    /** The tool-context key under which {@code ToolSearchToolCallingAdvisor} passes the session id. */
    static final String SESSION_ID_KEY = "toolSearchToolSessionId";

    private final CapabilityCatalog catalog;
    private final UnaryOperator<String> nameMapper;
    private final Function<String, CallerIdentity> caller;
    private final int limit;
    private final BiConsumer<ToolSearchRequest, DiscoveryQuery.Builder> queryCustomizer;
    private final BiConsumer<ToolSearchRequest, DiscoveryResult> onResult;

    private CatalogToolIndex(Builder b) {
        this.catalog = b.catalog;
        this.nameMapper = b.nameMapper;
        this.caller = b.caller;
        this.limit = b.limit;
        this.queryCustomizer = b.queryCustomizer;
        this.onResult = b.onResult;
    }

    /** An index with the defaults: anonymous caller, 5 hits unless the model asks for more. */
    public static CatalogToolIndex from(CapabilityCatalog catalog) {
        return builder(catalog).build();
    }

    public static Builder builder(CapabilityCatalog catalog) {
        return new Builder(catalog);
    }

    /** No-op: the catalog is the index. */
    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
    }

    /** No-op: the catalog is the index. */
    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
    }

    /** No-op: the catalog is the index. */
    @Override
    public void clearIndex(String sessionId) {
    }

    /**
     * Searches the catalog for the session's caller. {@code maxResults} is the hit limit (before
     * any fallback broadening); {@code categoryFilter} is read as comma-separated source names
     * and protocol ids of this catalog, and tokens that are neither are ignored, so a guessed
     * category never empties the result. Hits at every fallback stage are returned (at
     * {@code ALL_SCHEMAS}, every permitted capability); {@code NAMES_ONLY} candidates are not,
     * because the advisor loads the full definition of every returned name.
     */
    @Override
    public ToolSearchResponse search(ToolSearchRequest request) {
        long started = System.nanoTime();
        CallerIdentity who = caller.apply(request.sessionId());
        int max = request.maxResults() != null && request.maxResults() > 0 ? request.maxResults() : limit;
        DiscoveryQuery.Builder query = DiscoveryQuery.builder(request.query() == null ? "" : request.query())
                .limit(max)
                .caller(who == null ? CallerIdentity.ANONYMOUS : who);
        applyCategory(request.categoryFilter(), query);
        queryCustomizer.accept(request, query);
        DiscoveryResult result = catalog.search(query.build());
        onResult.accept(request, result);

        List<ToolReference> references = new ArrayList<>(result.hits().size());
        for (DiscoveryResult.Hit hit : result.hits()) {
            CapabilityCard card = hit.card();
            references.add(new ToolReference(nameMapper.apply(card.name()), hit.score(), card.summary()));
        }
        return new ToolSearchResponse(references, references.size(),
                new ToolSearchResponse.SearchMetadata(SEARCH_TYPE, request.query() == null ? "" : request.query(),
                        (System.nanoTime() - started) / 1_000_000));
    }

    private void applyCategory(String categoryFilter, DiscoveryQuery.Builder query) {
        if (categoryFilter == null || categoryFilter.isBlank()) {
            return;
        }
        Set<String> sources = catalog.sourceNames();
        Set<String> protocols = catalog.cards().stream().map(c -> c.protocol().value()).collect(Collectors.toSet());
        List<String> wantedSources = new ArrayList<>();
        List<String> wantedProtocols = new ArrayList<>();
        for (String token : categoryFilter.split(",")) {
            String t = token.trim();
            if (sources.contains(t)) {
                wantedSources.add(t);
            } else if (protocols.contains(t)) {
                wantedProtocols.add(t);
            }
        }
        if (!wantedSources.isEmpty()) {
            query.sources(wantedSources);
        }
        if (!wantedProtocols.isEmpty()) {
            query.protocols(wantedProtocols);
        }
    }

    /**
     * Every catalog capability as Spring AI callbacks, named like this index's hits. Register them
     * on the {@code ChatClient} together with the advisor: the advisor offers the model only the
     * callbacks whose names a search returned. Schemas come from the catalog's schema cache and
     * calls go through {@link CapabilityCatalog#invoke}, which re-checks permission and
     * availability. The caller is {@link ProtoweftToolCallbacks#CALLER_KEY} from the tool context
     * if present, else this index's caller function applied to the advisor's session id.
     */
    public ProtoweftToolCallbacks toolCallbacks() {
        return ProtoweftToolCallbacks.builder(new CatalogToolSource(catalog))
                .toolNames(nameMapper)
                .callerIdentityFrom(this::callerFor)
                .build();
    }

    private CallerIdentity callerFor(ToolContext context) {
        if (context != null) {
            Object explicit = context.getContext().get(ProtoweftToolCallbacks.CALLER_KEY);
            if (explicit instanceof CallerIdentity identity) {
                return identity;
            }
            Object session = context.getContext().get(SESSION_ID_KEY);
            if (session != null) {
                return caller.apply(session.toString());
            }
        }
        return CallerIdentity.ANONYMOUS;
    }

    /** Configures a {@link CatalogToolIndex}. */
    public static final class Builder {

        private final CapabilityCatalog catalog;
        private UnaryOperator<String> nameMapper = ToolNames::sanitize;
        private Function<String, CallerIdentity> caller = sessionId -> CallerIdentity.ANONYMOUS;
        private int limit = 5;
        private BiConsumer<ToolSearchRequest, DiscoveryQuery.Builder> queryCustomizer = (r, q) -> { };
        private BiConsumer<ToolSearchRequest, DiscoveryResult> onResult = (r, result) -> { };

        private Builder(CapabilityCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        /** Replaces {@link ToolNames#sanitize}, for both the hits and {@link #toolCallbacks()}. */
        public Builder toolNames(UnaryOperator<String> nameMapper) {
            this.nameMapper = Objects.requireNonNull(nameMapper, "nameMapper");
            return this;
        }

        /** A fixed caller for every search and call. */
        public Builder callerIdentity(Supplier<CallerIdentity> caller) {
            Objects.requireNonNull(caller, "caller");
            return callerIdentityFrom(sessionId -> caller.get());
        }

        /**
         * Derives the caller from the advisor's session id (by default the request's
         * {@code ChatMemory.CONVERSATION_ID}), so the caller's scopes filter the search.
         */
        public Builder callerIdentityFrom(Function<String, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /** Hits per search when neither the model nor the advisor sets {@code maxResults} (default 5). */
        public Builder limit(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be at least 1");
            }
            this.limit = limit;
            return this;
        }

        /** Tightens every query, for example to read-only capabilities or certain sources. */
        public Builder queryCustomizer(BiConsumer<ToolSearchRequest, DiscoveryQuery.Builder> customizer) {
            this.queryCustomizer = Objects.requireNonNull(customizer, "customizer");
            return this;
        }

        /**
         * Receives every catalog result, with its confidence, fallback stage and hidden counts,
         * which Spring AI's {@code SearchMetadata} has no fields for and the model never sees.
         */
        public Builder onResult(BiConsumer<ToolSearchRequest, DiscoveryResult> listener) {
            this.onResult = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public CatalogToolIndex build() {
            return new CatalogToolIndex(this);
        }
    }
}
