package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.ProtocolId;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Concise, searchable metadata about one capability: what a model sees before it selects
 * a tool. The full input schema is kept separately in the {@link SchemaCache} and sent only
 * when the capability is selected.
 *
 * @param name                 qualified name, {@code <source>.<tool>}
 * @param source               the source it was ingested from
 * @param tool                 the tool's name within its source
 * @param protocol             protocol of the source ({@code local} for in-JVM tools)
 * @param wireRevision         the revision negotiated with the peer, or {@code null}
 * @param sdkVersion           the connector's SDK version, or {@code null}
 * @param title                display title, or {@code null}
 * @param summary              first sentence of the description, at most ~160 characters
 * @param tags                 keywords
 * @param parameters           top-level input parameters
 * @param requiredScopes       scopes a caller must hold to see or call it
 * @param hints                declared behavior hints
 * @param schemaHash           SHA-256 of the canonical description and input schema
 * @param schemaTokensEstimate rough token size of the full schema (characters / 4)
 */
public record CapabilityCard(String name, String source, String tool, ProtocolId protocol, String wireRevision,
                             String sdkVersion, String title, String summary, List<String> tags,
                             List<Parameter> parameters, Set<String> requiredScopes, Set<CapabilityHint> hints,
                             String schemaHash, int schemaTokensEstimate) {

    /** One top-level input parameter. */
    public record Parameter(String name, boolean required) {
        public Parameter {
            Objects.requireNonNull(name, "name");
        }
    }

    public CapabilityCard {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(protocol, "protocol");
        summary = summary == null ? "" : summary;
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        hints = hints == null ? Set.of() : Set.copyOf(hints);
        Objects.requireNonNull(schemaHash, "schemaHash");
    }

    /** Parameter names, required ones marked with {@code *}. */
    public String parameterList() {
        StringBuilder out = new StringBuilder();
        for (Parameter p : parameters) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(p.name()).append(p.required() ? "*" : "");
        }
        return out.toString();
    }
}
