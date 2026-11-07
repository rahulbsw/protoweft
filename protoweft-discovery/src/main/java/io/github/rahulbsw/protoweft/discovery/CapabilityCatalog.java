package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolConnector;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A provider-neutral catalog of capabilities ingested from any {@link ToolSource}s (MCP
 * servers, UTCP manuals, A2A agents, local tools). It keeps concise {@link CapabilityCard}s
 * for search apart from full schemas in a {@link SchemaCache}, and answers
 * {@link #search(DiscoveryQuery)} with a few relevant capabilities instead of every schema.
 *
 * <p>Thread-safe. Call {@link #refresh()} when sources change their tool lists.
 */
public final class CapabilityCatalog {

    /** Protocol of sources that aren't protocol connectors. */
    public static final ProtocolId LOCAL = new ProtocolId("local");
    /** Protocol used for discovery's own observations. */
    public static final ProtocolId DISCOVERY = new ProtocolId("discovery");

    private static final int SUMMARY_LENGTH = 160;

    private record Source(String name, ToolSource tools, SourcePolicy policy) {
    }

    private final Map<String, Source> sources;
    private final Map<String, CapabilityCard> cards = new ConcurrentHashMap<>();
    private final Map<String, String> refreshFailures = new ConcurrentHashMap<>();
    private final Map<String, String> markedUnavailable = new ConcurrentHashMap<>();
    private final SchemaCache schemas = new SchemaCache();
    private final CapabilityIndex index;
    private final FallbackPolicy fallback;
    private final ObservationHandler observations;

    private CapabilityCatalog(Builder b) {
        this.sources = Map.copyOf(b.sources);
        this.index = b.index != null ? b.index : new HybridIndex(b.embedder);
        // Unless thresholds were set explicitly, use the preset calibrated for the embedder.
        this.fallback = b.fallback.confidence() == Confidence.Policy.DEFAULT && b.index == null
                ? b.fallback.withConfidence(Confidence.Policy.forEmbedder(b.embedder))
                : b.fallback;
        this.observations = b.observations;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ ingestion

    /** Re-lists every source. */
    public void refresh() {
        sources.keySet().forEach(this::refresh);
    }

    /**
     * Re-lists one source. Changed capabilities are re-indexed (and re-embedded); a source
     * whose listing fails keeps its previous cards and is marked unavailable.
     */
    public void refresh(String sourceName) {
        Source source = sources.get(sourceName);
        if (source == null) {
            throw new IllegalArgumentException("unknown source: " + sourceName);
        }
        List<ToolSpec> specs;
        try {
            specs = ObservationHandler.observe(observations,
                    new Observation(DISCOVERY, "catalog/refresh", sourceName), source.tools()::listTools);
        } catch (RuntimeException e) {
            refreshFailures.put(sourceName, "listing tools failed: " + e.getMessage());
            return;
        }
        refreshFailures.remove(sourceName);
        Set<String> seen = new HashSet<>();
        List<CapabilityIndex.Document> changed = new ArrayList<>();
        for (ToolSpec spec : specs) {
            String name = sourceName + ToolRegistry.SEPARATOR + spec.name();
            seen.add(name);
            CapabilityCard card = Cards.card(name, sourceName, spec, source.tools(), source.policy(), SUMMARY_LENGTH);
            boolean schemaChanged = schemas.put(name, card.schemaHash(), spec.withName(name));
            if (schemaChanged || !card.equals(cards.get(name))) {
                cards.put(name, card);
                changed.add(Cards.document(card, spec));
            }
        }
        List<String> removed = new ArrayList<>();
        for (CapabilityCard card : cards.values()) {
            if (card.source().equals(sourceName) && !seen.contains(card.name())) {
                removed.add(card.name());
            }
        }
        removed.forEach(n -> {
            cards.remove(n);
            schemas.remove(n);
        });
        index.remove(removed);
        index.upsert(changed);
    }

    /** Hides a source's capabilities as unavailable until {@link #markAvailable(String)}. */
    public void markUnavailable(String sourceName, String reason) {
        markedUnavailable.put(sourceName, reason == null ? "unavailable" : reason);
    }

    public void markAvailable(String sourceName) {
        markedUnavailable.remove(sourceName);
    }

    /** Why a source is unavailable, or empty if it is available. */
    public Optional<String> unavailableReason(String sourceName) {
        String reason = markedUnavailable.get(sourceName);
        if (reason == null) {
            reason = refreshFailures.get(sourceName);
        }
        if (reason == null) {
            Source source = sources.get(sourceName);
            if (source != null && source.policy().availability() != null
                    && !source.policy().availability().getAsBoolean()) {
                reason = "availability probe reported the source unavailable";
            }
        }
        return Optional.ofNullable(reason);
    }

    // ------------------------------------------------------------------ access

    public Collection<CapabilityCard> cards() {
        return List.copyOf(cards.values());
    }

    public Optional<CapabilityCard> card(String name) {
        return Optional.ofNullable(cards.get(name));
    }

    /** The full tool spec of a capability, named {@code <source>.<tool>}. */
    public Optional<ToolSpec> schema(String name) {
        return schemas.get(name);
    }

    public SchemaCache schemaCache() {
        return schemas;
    }

    public int size() {
        return cards.size();
    }

    public Set<String> sourceNames() {
        return sources.keySet();
    }

    public FallbackPolicy fallbackPolicy() {
        return fallback;
    }

    /** Whether a capability exists and the caller may see it (availability not checked). */
    public boolean visible(String name, CallerIdentity caller) {
        CapabilityCard card = cards.get(name);
        return card != null && DiscoveryQuery.builder("").caller(caller).build().permits(card);
    }

    // ------------------------------------------------------------------ search

    public DiscoveryResult search(DiscoveryQuery query) {
        Objects.requireNonNull(query, "query");
        return ObservationHandler.observe(observations, new Observation(DISCOVERY, "discovery/search", query.text()),
                () -> doSearch(query));
    }

    private DiscoveryResult doSearch(DiscoveryQuery query) {
        Map<String, Boolean> availability = new HashMap<>();
        Predicate<String> available = name -> availability.computeIfAbsent(cards.get(name).source(),
                s -> unavailableReason(s).isEmpty());
        Predicate<String> filtered = name -> {
            CapabilityCard c = cards.get(name);
            return c != null && query.matchesFilters(c);
        };
        Predicate<String> permitted = name -> query.permits(cards.get(name));
        Predicate<String> candidates = filtered.and(permitted)
                .and(name -> query.includeUnavailable() || available.test(name));

        CapabilityIndex.Result ranked = index.search(query.text(), candidates, query.limit(), fallback.minCosine());
        Confidence confidence = fallback.confidence().assess(ranked.hits(), ranked.semantic());
        FallbackPolicy.Stage stage = FallbackPolicy.Stage.NONE;
        List<CapabilityIndex.Hit> hits = ranked.hits();
        List<CapabilityCard> namesOnly = List.of();
        boolean inlineAll = false;

        if (confidence.level() == Confidence.Level.LOW) {
            switch (fallback.mode()) {
                case PROGRESSIVE -> {
                    hits = index.search(query.text(), candidates, query.limit() * fallback.broadenFactor(),
                            fallback.broadCosine()).hits();
                    stage = FallbackPolicy.Stage.BROADENED;
                    if (fallback.namesOnlyLimit() > 0) {
                        namesOnly = namesOnly(hits, candidates, fallback.namesOnlyLimit());
                        stage = FallbackPolicy.Stage.NAMES_ONLY;
                    }
                }
                case ALL_SCHEMAS -> {
                    hits = allCandidates(candidates);
                    stage = FallbackPolicy.Stage.ALL_SCHEMAS;
                    inlineAll = true;
                }
                case NONE -> {
                }
            }
        }

        Map<String, Integer> hiddenUnavailable = new LinkedHashMap<>();
        if (!query.includeUnavailable()) {
            Predicate<String> unavailableMatches = filtered.and(permitted).and(available.negate());
            for (CapabilityIndex.Hit h : index.search(query.text(), unavailableMatches, Integer.MAX_VALUE,
                    fallback.minCosine()).hits()) {
                hiddenUnavailable.merge(cards.get(h.id()).source(), 1, Integer::sum);
            }
        }
        int hiddenByPermission = index.search(query.text(), filtered.and(permitted.negate()), Integer.MAX_VALUE,
                fallback.minCosine()).hits().size();

        List<DiscoveryResult.Hit> out = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            CapabilityIndex.Hit h = hits.get(i);
            boolean inline = inlineAll || i < query.inlineSchemas();
            out.add(new DiscoveryResult.Hit(cards.get(h.id()), h.score(), inline ? schemas.get(h.id()).orElse(null) : null));
        }
        DiscoveryResult.Mode mode = ranked.semantic() ? DiscoveryResult.Mode.HYBRID : DiscoveryResult.Mode.KEYWORD_ONLY;
        return new DiscoveryResult(out, confidence, stage, namesOnly, hiddenUnavailable, hiddenByPermission, mode,
                cards.size());
    }

    /** Candidates not already returned, sources of the returned hits first. */
    private List<CapabilityCard> namesOnly(List<CapabilityIndex.Hit> hits, Predicate<String> candidates, int cap) {
        Set<String> returned = new HashSet<>();
        Set<String> hitSources = new HashSet<>();
        for (CapabilityIndex.Hit h : hits) {
            returned.add(h.id());
            hitSources.add(cards.get(h.id()).source());
        }
        List<CapabilityCard> preferred = new ArrayList<>();
        List<CapabilityCard> rest = new ArrayList<>();
        for (CapabilityCard card : cards.values()) {
            if (!returned.contains(card.name()) && candidates.test(card.name())) {
                (hitSources.contains(card.source()) ? preferred : rest).add(card);
            }
        }
        java.util.Comparator<CapabilityCard> byName = java.util.Comparator.comparing(CapabilityCard::name);
        preferred.sort(byName);
        rest.sort(byName);
        preferred.addAll(rest);
        return preferred.subList(0, Math.min(cap, preferred.size()));
    }

    private List<CapabilityIndex.Hit> allCandidates(Predicate<String> candidates) {
        List<CapabilityIndex.Hit> all = new ArrayList<>();
        cards.keySet().stream().filter(candidates).sorted()
                .forEach(id -> all.add(new CapabilityIndex.Hit(id, 0, 0, 0, 0, 0, Double.NaN)));
        return all;
    }

    // ------------------------------------------------------------------ invocation

    /**
     * Calls a capability on behalf of {@code caller}, re-checking permission and availability.
     * A capability the caller may not see is reported exactly like an unknown one.
     */
    public ToolOutcome invoke(String name, Map<String, Object> arguments, CallerIdentity caller) {
        return invoke(name, arguments, caller, io.github.rahulbsw.protoweft.core.tool.ToolProgress.NOOP);
    }

    /** As {@link #invoke(String, Map, CallerIdentity)}, forwarding the tool's progress and log messages. */
    public ToolOutcome invoke(String name, Map<String, Object> arguments, CallerIdentity caller,
                              io.github.rahulbsw.protoweft.core.tool.ToolProgress progress) {
        return invoke(name, arguments, caller, progress, io.github.rahulbsw.protoweft.core.tool.ToolContexts.EMPTY);
    }

    /** As above, also passing the serving protocol's per-call contexts (for example RAP's) to the tool. */
    public ToolOutcome invoke(String name, Map<String, Object> arguments, CallerIdentity caller,
                              io.github.rahulbsw.protoweft.core.tool.ToolProgress progress, io.github.rahulbsw.protoweft.core.tool.ToolContexts contexts) {
        CapabilityCard card = cards.get(name);
        if (card == null || !visible(name, caller)) {
            throw new ProtoweftException(ErrorCategory.TOOL_NOT_FOUND, DISCOVERY, "unknown capability: " + name);
        }
        Optional<String> unavailable = unavailableReason(card.source());
        if (unavailable.isPresent()) {
            return ToolOutcome.failure("capability " + name + " is unavailable: " + unavailable.get());
        }
        return sources.get(card.source()).tools().invoke(ToolInvocation.of(card.tool(), arguments), caller, progress,
                contexts);
    }

    /** Builds a {@link CapabilityCatalog} and ingests every source. */
    public static final class Builder {
        private final Map<String, Source> sources = new LinkedHashMap<>();
        private Embedder embedder;
        private CapabilityIndex index;
        private FallbackPolicy fallback = FallbackPolicy.DEFAULT;
        private ObservationHandler observations = ObservationHandler.NOOP;

        public Builder source(String name, ToolSource tools) {
            return source(name, tools, SourcePolicy.DEFAULT);
        }

        public Builder source(String name, ToolSource tools, SourcePolicy policy) {
            Objects.requireNonNull(tools, "tools");
            Objects.requireNonNull(policy, "policy");
            if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("source name must match [A-Za-z0-9_-]+: " + name);
            }
            if (sources.putIfAbsent(name, new Source(name, tools, policy)) != null) {
                throw new IllegalArgumentException("duplicate source: " + name);
            }
            return this;
        }

        /** Adds every source of a registry under its registered name. */
        public Builder registry(ToolRegistry registry) {
            registry.sources().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> source(e.getKey(), e.getValue()));
            return this;
        }

        /** Enables semantic ranking; without an embedder search is keyword-only. */
        public Builder embedder(Embedder embedder) {
            this.embedder = embedder;
            return this;
        }

        /** Replaces the default {@link HybridIndex}; the embedder setting is then ignored. */
        public Builder index(CapabilityIndex index) {
            this.index = index;
            return this;
        }

        public Builder fallback(FallbackPolicy fallback) {
            this.fallback = Objects.requireNonNull(fallback, "fallback");
            return this;
        }

        public Builder observations(ObservationHandler observations) {
            this.observations = Objects.requireNonNull(observations, "observations");
            return this;
        }

        public CapabilityCatalog build() {
            CapabilityCatalog catalog = new CapabilityCatalog(this);
            catalog.refresh();
            return catalog;
        }
    }
}
