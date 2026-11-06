package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A capability search. Exact filters (protocols, versions, sources, hints, the caller's
 * permissions and availability) are applied before ranking, so they never trade off against
 * relevance.
 *
 * @param text               what the model is looking for
 * @param limit              how many hits to return before any fallback broadening
 * @param protocols          protocol ids to keep (empty = any), for example {@code mcp}
 * @param versions           exact wire revisions to keep (empty = any)
 * @param sources            source names to keep (empty = any)
 * @param requireHints       hints a capability must declare
 * @param excludeHints       hints a capability must not declare
 * @param caller             who is searching; capabilities whose required scopes the caller
 *                           doesn't hold are invisible
 * @param includeUnavailable whether to return capabilities of unavailable sources
 * @param inlineSchemas      include full schemas for the top this-many hits (0 = cards only)
 */
public record DiscoveryQuery(String text, int limit, Set<String> protocols, Set<String> versions, Set<String> sources,
                             Set<CapabilityHint> requireHints, Set<CapabilityHint> excludeHints, CallerIdentity caller,
                             boolean includeUnavailable, int inlineSchemas) {

    /** Caller attribute holding the granted scopes, as a {@code Collection<String>}. */
    public static final String SCOPES_ATTRIBUTE = "scopes";

    public DiscoveryQuery {
        text = text == null ? "" : text;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (inlineSchemas < 0) {
            throw new IllegalArgumentException("inlineSchemas must not be negative");
        }
        protocols = protocols == null ? Set.of() : Set.copyOf(protocols);
        versions = versions == null ? Set.of() : Set.copyOf(versions);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        requireHints = requireHints == null ? Set.of() : Set.copyOf(requireHints);
        excludeHints = excludeHints == null ? Set.of() : Set.copyOf(excludeHints);
        caller = caller == null ? CallerIdentity.ANONYMOUS : caller;
    }

    public static Builder builder(String text) {
        return new Builder(text);
    }

    /** Scopes granted to the caller, from {@link #SCOPES_ATTRIBUTE}. */
    Set<String> callerScopes() {
        return caller.attributes().get(SCOPES_ATTRIBUTE) instanceof java.util.Collection<?> c
                ? c.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                : Set.of();
    }

    /** Whether the caller may see a capability (exact permission filter). */
    boolean permits(CapabilityCard card) {
        return card.requiredScopes().isEmpty() || callerScopes().containsAll(card.requiredScopes());
    }

    /** Whether a capability passes the protocol, version, source and hint filters. */
    boolean matchesFilters(CapabilityCard card) {
        return (protocols.isEmpty() || protocols.contains(card.protocol().value()))
                && (versions.isEmpty() || (card.wireRevision() != null && versions.contains(card.wireRevision())))
                && (sources.isEmpty() || sources.contains(card.source()))
                && card.hints().containsAll(requireHints)
                && excludeHints.stream().noneMatch(card.hints()::contains);
    }

    /** Results returned when a query sets no limit. */
    public static final int DEFAULT_LIMIT = 5;

    /** Builds a {@link DiscoveryQuery}. */
    public static final class Builder {
        private final String text;
        private int limit = DEFAULT_LIMIT;
        private Set<String> protocols = Set.of();
        private Set<String> versions = Set.of();
        private Set<String> sources = Set.of();
        private final Set<CapabilityHint> requireHints = EnumSet.noneOf(CapabilityHint.class);
        private final Set<CapabilityHint> excludeHints = EnumSet.noneOf(CapabilityHint.class);
        private CallerIdentity caller = CallerIdentity.ANONYMOUS;
        private boolean includeUnavailable;
        private int inlineSchemas;

        private Builder(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder protocols(String... protocols) {
            this.protocols = Set.of(protocols);
            return this;
        }

        public Builder versions(String... versions) {
            this.versions = Set.of(versions);
            return this;
        }

        public Builder sources(String... sources) {
            this.sources = Set.of(sources);
            return this;
        }

        public Builder sources(List<String> sources) {
            this.sources = Set.copyOf(sources);
            return this;
        }

        public Builder protocols(List<String> protocols) {
            this.protocols = Set.copyOf(protocols);
            return this;
        }

        public Builder require(CapabilityHint... hints) {
            requireHints.addAll(List.of(hints));
            return this;
        }

        public Builder exclude(CapabilityHint... hints) {
            excludeHints.addAll(List.of(hints));
            return this;
        }

        public Builder caller(CallerIdentity caller) {
            this.caller = Objects.requireNonNull(caller, "caller");
            return this;
        }

        public Builder includeUnavailable(boolean include) {
            this.includeUnavailable = include;
            return this;
        }

        public Builder inlineSchemas(int count) {
            this.inlineSchemas = count;
            return this;
        }

        public DiscoveryQuery build() {
            return new DiscoveryQuery(text, limit, protocols, versions, sources, requireHints, excludeHints, caller,
                    includeUnavailable, inlineSchemas);
        }
    }
}
