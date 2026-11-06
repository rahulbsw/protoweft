package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.discovery.CapabilityCard;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import io.github.rahulbsw.protoweft.springai.ProtoweftToolCallbacks;
import io.github.rahulbsw.protoweft.springai.CatalogToolIndex;
import io.github.rahulbsw.protoweft.springai.ToolNames;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Catalog-based tool selection for ARGI agents: an alternative to ARGI's
 * {@code ToolSelectionInterceptor} that asks the {@link CapabilityCatalog} instead of an extra
 * LLM call.
 *
 * <pre>{@code
 * ReactAgent agent = ReactAgent.builder()
 *         .name("assistant").model(chatModel)
 *         .interceptors(CatalogToolSelectionInterceptor.builder(catalog).limit(5).build())
 *         .saver(new MemorySaver())
 *         .build();   // the catalog's tools are registered by the interceptor itself
 * }</pre>
 *
 * <p>On every model call it searches the catalog with the text of the latest user message (plus,
 * optionally, recent messages before it) for the request's caller, and offers the model only the
 * catalog tools found, plus {@linkplain Builder#alwaysVisible always-visible} tools and (by
 * default) tools that are not in the catalog. The catalog's permission filter, availability
 * filter, ranking and fallback apply: hits at every fallback stage are offered ({@code BROADENED},
 * and {@code ALL_SCHEMAS}, which is every permitted capability), {@code NAMES_ONLY} candidates are
 * not. When the search finds nothing (or fails, or there is no user text), the
 * {@linkplain Builder#whenNothingFound policy} decides: {@link NoMatch#KEEP_ALL} (the default,
 * like ARGI's selector, which falls back to every tool) still offers only the catalog tools the
 * caller may see and whose source is available.
 *
 * <p>The caller is read from the request context, which ARGI fills from the run's
 * {@code RunnableConfig} metadata ({@link ArgiTools#CALLER_KEY}, set by {@link ArgiAgents}).
 * Tool calls go through {@link CapabilityCatalog#invoke}, which re-checks permission and
 * availability, so a guessed name of a hidden tool still fails. Thread-safe.
 */
public final class CatalogToolSelectionInterceptor extends ModelInterceptor {

    /** What to offer when the catalog finds nothing for a request. */
    public enum NoMatch {
        /** Every catalog tool the caller may see whose source is available. */
        KEEP_ALL,
        /** No catalog tools (always-visible and uncatalogued tools are still offered). */
        KEEP_NONE
    }

    /** The interceptor's name in ARGI. */
    public static final String NAME = "ProtoweftCatalogToolSelection";

    /**
     * A tool name no callback has: ARGI treats an empty tool list as "all tools", so offering no
     * tools is expressed as a list that matches none.
     */
    static final String NO_TOOLS = "__protoweft_no_tools__";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogToolSelectionInterceptor.class);

    private final CapabilityCatalog catalog;
    private final UnaryOperator<String> nameMapper;
    private final Function<ModelRequest, CallerIdentity> caller;
    private final int limit;
    private final int contextMessages;
    private final Set<String> alwaysVisible;
    private final boolean keepUncatalogued;
    private final NoMatch noMatch;
    private final boolean registerCatalogTools;
    private final BiConsumer<ModelRequest, DiscoveryQuery.Builder> queryCustomizer;
    private final BiConsumer<ModelRequest, DiscoveryResult> onResult;
    private final ProtoweftToolCallbacks callbacks;

    private CatalogToolSelectionInterceptor(Builder b) {
        this.catalog = b.catalog;
        this.nameMapper = b.nameMapper;
        this.caller = b.caller;
        this.limit = b.limit;
        this.contextMessages = b.contextMessages;
        this.alwaysVisible = Set.copyOf(b.alwaysVisible);
        this.keepUncatalogued = b.keepUncatalogued;
        this.noMatch = b.noMatch;
        this.registerCatalogTools = b.registerCatalogTools;
        this.queryCustomizer = b.queryCustomizer;
        this.onResult = b.onResult;
        // Same names as the hits; calls go through CapabilityCatalog.invoke as the caller in
        // the tool context (ARGI copies the run's metadata, including protoweft.caller, into it).
        this.callbacks = CatalogToolIndex.builder(catalog).toolNames(nameMapper).build().toolCallbacks();
    }

    /** An interceptor with the defaults (5 hits, caller from the request, keep all on no match). */
    public static CatalogToolSelectionInterceptor from(CapabilityCatalog catalog) {
        return builder(catalog).build();
    }

    public static Builder builder(CapabilityCatalog catalog) {
        return new Builder(catalog);
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Every catalog capability as a callback, named like the hits, so ARGI registers them on the
     * agent (it collects interceptor tools when the agent is built). Empty when
     * {@linkplain Builder#registerCatalogTools registration} is off.
     */
    @Override
    public List<ToolCallback> getTools() {
        return registerCatalogTools ? List.of(callbacks.getToolCallbacks()) : List.of();
    }

    /** The callbacks for every catalog capability, for registering them on the agent yourself. */
    public ProtoweftToolCallbacks toolCallbacks() {
        return callbacks;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<String> available = request.getTools();
        if (available == null || available.isEmpty()) {
            return handler.call(request);
        }
        Map<String, String> catalogNames = catalogNames();
        CallerIdentity who = caller.apply(request);
        Set<String> chosen = select(request, who == null ? CallerIdentity.ANONYMOUS : who, catalogNames);

        List<String> offered = new ArrayList<>();
        for (String name : available) {
            boolean catalogued = catalogNames.containsKey(name);
            if (alwaysVisible.contains(name) || (catalogued ? chosen.contains(name) : keepUncatalogued)) {
                offered.add(name);
            }
        }
        if (offered.isEmpty()) {
            offered.add(NO_TOOLS);
        }
        return handler.call(ModelRequest.builder(request).tools(offered).build());
    }

    /** Provider name of each catalog capability, mapped to its catalog name. */
    private Map<String, String> catalogNames() {
        Map<String, String> names = new HashMap<>();
        for (CapabilityCard card : catalog.cards()) {
            names.put(nameMapper.apply(card.name()), card.name());
        }
        return names;
    }

    private Set<String> select(ModelRequest request, CallerIdentity who, Map<String, String> catalogNames) {
        String text = queryText(request.getMessages());
        if (text.isBlank()) {
            return whenNothingFound(who, catalogNames);
        }
        DiscoveryResult result;
        try {
            DiscoveryQuery.Builder query = DiscoveryQuery.builder(text).limit(limit).caller(who);
            queryCustomizer.accept(request, query);
            result = catalog.search(query.build());
        } catch (RuntimeException e) {
            LOG.warn("Catalog tool selection failed, applying the {} policy: {}", noMatch, e.toString());
            return whenNothingFound(who, catalogNames);
        }
        onResult.accept(request, result);
        Set<String> hits = new LinkedHashSet<>();
        for (DiscoveryResult.Hit hit : result.hits()) {
            hits.add(nameMapper.apply(hit.card().name()));
        }
        return hits.isEmpty() ? whenNothingFound(who, catalogNames) : hits;
    }

    private Set<String> whenNothingFound(CallerIdentity who, Map<String, String> catalogNames) {
        Set<String> all = new LinkedHashSet<>();
        if (noMatch == NoMatch.KEEP_ALL) {
            catalogNames.forEach((providerName, name) -> {
                if (catalog.visible(name, who) && catalog.card(name)
                        .map(c -> catalog.unavailableReason(c.source()).isEmpty()).orElse(false)) {
                    all.add(providerName);
                }
            });
        }
        return all;
    }

    /** The latest user message, preceded by up to {@code contextMessages} earlier user/assistant texts. */
    String queryText(List<Message> messages) {
        if (messages == null) {
            return "";
        }
        int last = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = last - 1; i >= 0 && parts.size() < contextMessages; i--) {
            Message m = messages.get(i);
            if ((m instanceof UserMessage || m instanceof AssistantMessage) && m.getText() != null
                    && !m.getText().isBlank()) {
                parts.add(0, m.getText());
            }
        }
        String lastText = messages.get(last).getText();
        parts.add(lastText == null ? "" : lastText);
        return String.join("\n", parts).trim();
    }

    /** Configures a {@link CatalogToolSelectionInterceptor}. */
    public static final class Builder {

        private final CapabilityCatalog catalog;
        private UnaryOperator<String> nameMapper = ToolNames::sanitize;
        private Function<ModelRequest, CallerIdentity> caller = r -> ArgiTools.callerFrom(r.getContext());
        private int limit = 5;
        private int contextMessages;
        private final Set<String> alwaysVisible = new LinkedHashSet<>();
        private boolean keepUncatalogued = true;
        private NoMatch noMatch = NoMatch.KEEP_ALL;
        private boolean registerCatalogTools = true;
        private BiConsumer<ModelRequest, DiscoveryQuery.Builder> queryCustomizer = (r, q) -> { };
        private BiConsumer<ModelRequest, DiscoveryResult> onResult = (r, result) -> { };

        private Builder(CapabilityCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        /** Hits per search, before any fallback broadening (default 5). */
        public Builder limit(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be at least 1");
            }
            this.limit = limit;
            return this;
        }

        /** Also searches with up to this many earlier user/assistant message texts (default 0). */
        public Builder contextMessages(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            this.contextMessages = count;
            return this;
        }

        /** Tools (by provider name, for example {@code local__help}) offered on every call. */
        public Builder alwaysVisible(String... providerToolNames) {
            this.alwaysVisible.addAll(Arrays.asList(providerToolNames));
            return this;
        }

        /**
         * Whether the agent's tools that are not catalog capabilities (hook or interceptor tools,
         * plain Spring AI callbacks) are offered on every call (default {@code true}); the catalog
         * can't rank them.
         */
        public Builder keepUncataloguedTools(boolean keep) {
            this.keepUncatalogued = keep;
            return this;
        }

        /** What to offer when the search finds nothing, fails, or there is no user text (default {@link NoMatch#KEEP_ALL}). */
        public Builder whenNothingFound(NoMatch policy) {
            this.noMatch = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Whether {@link #getTools()} hands ARGI the catalog's callbacks to register (default
         * {@code true}); turn off when registering {@link #toolCallbacks()} yourself.
         */
        public Builder registerCatalogTools(boolean register) {
            this.registerCatalogTools = register;
            return this;
        }

        /** Replaces {@link ToolNames#sanitize} for both the hits and the callbacks. */
        public Builder toolNames(UnaryOperator<String> nameMapper) {
            this.nameMapper = Objects.requireNonNull(nameMapper, "nameMapper");
            return this;
        }

        /** A fixed caller for every search. */
        public Builder callerIdentity(Supplier<CallerIdentity> caller) {
            Objects.requireNonNull(caller, "caller");
            return callerIdentityFrom(r -> caller.get());
        }

        /**
         * Derives the caller from the model request (its context holds the agent state and the
         * run's metadata), replacing the default lookup of {@link ArgiTools#CALLER_KEY}.
         */
        public Builder callerIdentityFrom(Function<ModelRequest, CallerIdentity> caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        /** Tightens every query, for example to read-only capabilities or certain sources. */
        public Builder queryCustomizer(BiConsumer<ModelRequest, DiscoveryQuery.Builder> customizer) {
            this.queryCustomizer = Objects.requireNonNull(customizer, "customizer");
            return this;
        }

        /** Receives every catalog result, with its confidence, fallback stage and hidden counts. */
        public Builder onResult(BiConsumer<ModelRequest, DiscoveryResult> listener) {
            this.onResult = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public CatalogToolSelectionInterceptor build() {
            return new CatalogToolSelectionInterceptor(this);
        }
    }
}
