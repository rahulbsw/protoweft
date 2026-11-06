package io.github.rahulbsw.protoweft.core.tool;

import java.util.Map;
import java.util.Objects;

/**
 * A protocol-neutral description of a callable tool.
 *
 * @param name        the tool's identifier, unique within one source
 * @param description human-readable description, possibly empty
 * @param inputSchema JSON Schema for the arguments, as plain maps/lists/scalars
 * @param metadata    protocol-neutral facts about the tool, keyed by the {@code META_*}
 *                    constants (behavior hints, title, tags); unknown keys are allowed
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema,
                       Map<String, Object> metadata) {

    /** {@code Boolean}: the tool does not modify its environment (MCP {@code readOnlyHint}). */
    public static final String META_READ_ONLY = "readOnly";
    /** {@code Boolean}: the tool may perform destructive updates (MCP {@code destructiveHint}). */
    public static final String META_DESTRUCTIVE = "destructive";
    /** {@code Boolean}: repeated calls with the same arguments have no additional effect. */
    public static final String META_IDEMPOTENT = "idempotent";
    /** {@code Boolean}: the tool interacts with an open world of external entities. */
    public static final String META_OPEN_WORLD = "openWorld";
    /** {@code String}: a human-friendly display title. */
    public static final String META_TITLE = "title";
    /** {@code List<String>}: keywords, for example UTCP tool tags. */
    public static final String META_TAGS = "tags";

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A tool spec without metadata. */
    public ToolSpec(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, Map.of());
    }

    /** A copy with a different name and the same description, schema and metadata. */
    public ToolSpec withName(String newName) {
        return new ToolSpec(newName, description, inputSchema, metadata);
    }
}
