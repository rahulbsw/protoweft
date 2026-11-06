package io.github.rahulbsw.protoweft.discovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * How a catalog treats one source: the scopes its capabilities require, extra tags, an
 * availability probe, and protocol/version overrides for sources that aren't connectors.
 */
public final class SourcePolicy {

    public static final SourcePolicy DEFAULT = builder().build();

    private final Set<String> requiredScopes;
    private final Map<String, Set<String>> toolScopes;
    private final List<String> tags;
    private final BooleanSupplier availability;
    private final String protocol;
    private final String wireRevision;

    private SourcePolicy(Builder b) {
        this.requiredScopes = Set.copyOf(b.requiredScopes);
        this.toolScopes = Map.copyOf(b.toolScopes);
        this.tags = List.copyOf(b.tags);
        this.availability = b.availability;
        this.protocol = b.protocol;
        this.wireRevision = b.wireRevision;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Scopes required for {@code tool}: a per-tool override, else the source default. */
    Set<String> scopesFor(String tool) {
        return toolScopes.getOrDefault(tool, requiredScopes);
    }

    List<String> tags() {
        return tags;
    }

    /** {@code null} when no probe is configured. */
    BooleanSupplier availability() {
        return availability;
    }

    String protocol() {
        return protocol;
    }

    String wireRevision() {
        return wireRevision;
    }

    /** Builds a {@link SourcePolicy}. */
    public static final class Builder {
        private Set<String> requiredScopes = Set.of();
        private final Map<String, Set<String>> toolScopes = new HashMap<>();
        private List<String> tags = List.of();
        private BooleanSupplier availability;
        private String protocol;
        private String wireRevision;

        /** Scopes a caller needs to see or call any tool of the source. */
        public Builder requiredScopes(String... scopes) {
            this.requiredScopes = Set.of(scopes);
            return this;
        }

        /** Scopes for one tool, replacing the source default for it. */
        public Builder toolScopes(String tool, String... scopes) {
            toolScopes.put(Objects.requireNonNull(tool, "tool"), Set.of(scopes));
            return this;
        }

        /** Tags added to every capability of the source. */
        public Builder tags(String... tags) {
            this.tags = List.of(tags);
            return this;
        }

        /** Called at search time; {@code false} hides the source's capabilities as unavailable. */
        public Builder availability(BooleanSupplier probe) {
            this.availability = Objects.requireNonNull(probe, "probe");
            return this;
        }

        /** Protocol id for sources that aren't protocol connectors (default {@code local}). */
        public Builder protocol(String protocol) {
            this.protocol = Objects.requireNonNull(protocol, "protocol");
            return this;
        }

        /** Wire revision for sources that aren't protocol connectors. */
        public Builder wireRevision(String revision) {
            this.wireRevision = revision;
            return this;
        }

        public SourcePolicy build() {
            return new SourcePolicy(this);
        }
    }
}
