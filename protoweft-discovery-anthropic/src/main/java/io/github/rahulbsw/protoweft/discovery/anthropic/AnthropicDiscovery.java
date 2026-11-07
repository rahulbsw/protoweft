package io.github.rahulbsw.protoweft.discovery.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolReferenceBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolSearchToolResultBlock;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.AnthropicPayloads;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoverySession;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Anthropic tool search (Messages API) with official Anthropic Java SDK types, backed by a
 * {@link CapabilityCatalog}. Protoweft makes no model calls: put {@link #tools(DiscoveryQuery)}
 * into your own {@code MessageCreateParams}, send it with your {@code AnthropicClient}, and pass
 * the response to {@link #process(Message)}.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@link Mode#CATALOG_SEARCH} (default): every permitted, available capability is sent with
 *       {@code defer_loading: true}, plus a non-deferred {@code search_capabilities} tool. When the
 *       model calls it, {@link #handle(ToolUseBlock)} runs the catalog search for the caller and
 *       answers with a {@code tool_result} of {@code tool_reference} blocks, which makes Anthropic
 *       load those schemas.
 *   <li>{@link Mode#HOSTED_REGEX} / {@link Mode#HOSTED_BM25}: the same deferred tools plus
 *       Anthropic's hosted search tool; Anthropic searches, and its {@code server_tool_use} and
 *       {@code tool_search_tool_result} blocks are echoed back unchanged in the assistant message.
 * </ul>
 *
 * <p>The wire JSON equals that of {@link AnthropicPayloads}, which this class delegates to for
 * filtering and naming. Use one instance (or at least one {@link io.github.rahulbsw.protoweft.discovery.ProviderToolNames})
 * per conversation, so provider names map back to the same capabilities. Thread-safe.
 *
 * @see <a href="https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool">Tool search tool</a>
 */
public final class AnthropicDiscovery {

    /** Who searches the deferred tools. */
    public enum Mode {
        /** Our catalog, through the {@code search_capabilities} tool. */
        CATALOG_SEARCH,
        /** Anthropic's hosted {@code tool_search_tool_regex_20251119}. */
        HOSTED_REGEX,
        /** Anthropic's hosted {@code tool_search_tool_bm25_20251119}. */
        HOSTED_BM25
    }

    private final CapabilityCatalog catalog;
    private final DiscoverySession session;
    private final CallerIdentity caller;
    private final Mode mode;
    private final boolean keepSelectedLoaded;
    private final AnthropicPayloads raw;

    private AnthropicDiscovery(Builder b) {
        this.catalog = b.catalog;
        this.session = b.session;
        this.caller = b.session != null ? b.session.caller() : b.caller;
        this.mode = b.mode;
        this.keepSelectedLoaded = b.keepSelectedLoaded;
        this.raw = b.names != null ? new AnthropicPayloads(b.catalog, b.names) : new AnthropicPayloads(b.catalog);
    }

    /** Catalog search, anonymous caller, no session. */
    public static AnthropicDiscovery of(CapabilityCatalog catalog) {
        return builder(catalog).build();
    }

    public static Builder builder(CapabilityCatalog catalog) {
        return new Builder(catalog);
    }

    public Mode mode() {
        return mode;
    }

    public CallerIdentity caller() {
        return caller;
    }

    public Optional<DiscoverySession> session() {
        return Optional.ofNullable(session);
    }

    /** The name mapping shared with the raw payloads. */
    public io.github.rahulbsw.protoweft.discovery.ProviderToolNames names() {
        return raw.names();
    }

    // ------------------------------------------------------------------ request parts

    /**
     * The {@code tools} of a request: the search tool (ours or hosted, never deferred) followed
     * by every capability the filter allows, deferred. Only the filter's exact checks apply; its
     * text and limit are ignored, and its caller is replaced by this instance's caller, so the
     * tools sent match what {@link #handle(ToolUseBlock)} will let that caller call. With {@link Builder#keepSelectedLoaded(boolean)}, capabilities
     * already selected in the session are sent without {@code defer_loading}.
     *
     * @throws IllegalStateException if more than {@link AnthropicPayloads#MAX_DEFERRED_TOOLS}
     *                               capabilities would be deferred
     */
    public List<ToolUnion> tools(DiscoveryQuery filter) {
        Objects.requireNonNull(filter, "filter");
        filter = new DiscoveryQuery(filter.text(), filter.limit(), filter.protocols(), filter.versions(),
                filter.sources(), filter.requireHints(), filter.excludeHints(), caller, filter.includeUnavailable(),
                filter.inlineSchemas());
        List<ToolUnion> tools = new ArrayList<>();
        tools.add(mode == Mode.CATALOG_SEARCH ? ToolUnion.ofTool(searchTool()) : hostedSearchTool(mode));
        Set<String> selected = keepSelectedLoaded && session != null ? new HashSet<>(session.loaded()) : Set.of();
        for (Map<String, Object> tool : raw.deferredTools(filter)) {
            String catalogName = raw.catalogName((String) tool.get("name")).orElse(null);
            if (catalogName != null && selected.contains(catalogName)) {
                tool = new LinkedHashMap<>(tool);
                tool.remove("defer_loading");
            }
            tools.add(ToolUnion.ofTool(TypedTools.tool(tool)));
        }
        return tools;
    }

    /** All permitted, available capabilities for this caller. */
    public List<ToolUnion> tools() {
        return tools(DiscoveryQuery.builder("").build());
    }

    /** A capability as an SDK {@link Tool}, deferred or not. */
    public Tool tool(ToolSpec spec, boolean deferred) {
        return TypedTools.tool(raw.tool(spec, deferred));
    }

    /** Our catalog-backed {@code search_capabilities} tool (never deferred). */
    public Tool searchTool() {
        return TypedTools.tool(raw.customSearchTool());
    }

    /** Anthropic's hosted search tool for {@link Mode#HOSTED_REGEX} or {@link Mode#HOSTED_BM25}. */
    public static ToolUnion hostedSearchTool(Mode mode) {
        return TypedTools.hostedSearchTool(Objects.requireNonNull(mode, "mode"));
    }

    /** A {@code tool_result} whose content references catalog capabilities by provider name. */
    public ToolResultBlockParam toolReferenceResult(String toolUseId, List<String> catalogNames) {
        Objects.requireNonNull(toolUseId, "toolUseId");
        List<ToolResultBlockParam.Content.Block> refs = new ArrayList<>();
        for (String name : catalogNames) {
            refs.add(ToolResultBlockParam.Content.Block.ofToolReference(names().providerName(name)));
        }
        return ToolResultBlockParam.builder().toolUseId(toolUseId).contentOfBlocks(refs).build();
    }

    // ------------------------------------------------------------------ responses

    /**
     * Answers one {@code tool_use} block.
     * <ul>
     *   <li>{@code search_capabilities} (catalog mode): runs the catalog search for the caller,
     *       records the hits in the session, and returns {@code tool_reference} content.
     *   <li>A capability: maps the provider name back and calls {@link CapabilityCatalog#invoke};
     *       the outcome text becomes the content, with {@code is_error} for error outcomes.
     *   <li>Anything else, including capabilities the caller may not see: the same
     *       {@code unknown tool} error.
     * </ul>
     * Exceptions other than {@link ProtoweftException} thrown by a tool propagate.
     */
    public ToolResultBlockParam handle(ToolUseBlock toolUse) {
        String id = toolUse.id();
        String name = toolUse.name();
        Map<String, Object> input = arguments(toolUse._input());
        if (mode == Mode.CATALOG_SEARCH && DiscoveryTools.SEARCH.equals(name)) {
            return search(id, input);
        }
        Optional<String> catalogName = raw.catalogName(name);
        if (catalogName.isEmpty()) {
            return unknown(id, name);
        }
        ToolOutcome outcome;
        try {
            outcome = catalog.invoke(catalogName.get(), input, caller).dropSubscription();
        } catch (ProtoweftException e) {
            return e.category() == ErrorCategory.TOOL_NOT_FOUND ? unknown(id, name) : error(id, e.getMessage());
        }
        ToolResultBlockParam.Builder result = ToolResultBlockParam.builder().toolUseId(id);
        String text = render(outcome);
        if (!text.isEmpty()) {
            result.content(text);
        }
        if (outcome.error()) {
            result.isError(true);
        }
        return result.build();
    }

    /**
     * Processes a whole response: answers every {@code tool_use} block with
     * {@link #handle(ToolUseBlock)} and keeps the response as the assistant message to append
     * (hosted-search blocks included, unchanged). Capabilities found by a hosted search are also
     * recorded in the session.
     */
    public AnthropicTurn process(Message response) {
        List<ToolResultBlockParam> results = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                results.add(handle(block.asToolUse()));
            } else if (block.isToolSearchToolResult()) {
                recordHostedSelection(block.asToolSearchToolResult());
            }
        }
        return new AnthropicTurn(response.toParam(), results);
    }

    private ToolResultBlockParam search(String id, Map<String, Object> input) {
        List<String> hits;
        try {
            hits = catalog.search(DiscoveryTools.query(input, caller)).names();
        } catch (ProtoweftException e) {
            return error(id, e.getMessage());
        }
        if (session != null) {
            session.select(hits);
        }
        return toolReferenceResult(id, hits);
    }

    private void recordHostedSelection(ToolSearchToolResultBlock block) {
        if (session == null || !block.content().isToolSearchToolSearchResultBlock()) {
            return;
        }
        List<String> found = new ArrayList<>();
        for (ToolReferenceBlock ref : block.content().asToolSearchToolSearchResultBlock().toolReferences()) {
            raw.catalogName(ref.toolName()).ifPresent(found::add);
        }
        session.select(found);
    }

    private static ToolResultBlockParam unknown(String id, String name) {
        return error(id, "unknown tool: " + name);
    }

    private static ToolResultBlockParam error(String id, String message) {
        return ToolResultBlockParam.builder().toolUseId(id).content(message == null ? "error" : message)
                .isError(true).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(JsonValue input) {
        if (input.asObject().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = input.convert(Map.class);
        return map == null ? Map.of() : map;
    }

    /** Text of an outcome; non-text parts are described, never dropped silently. */
    static String render(ToolOutcome outcome) {
        List<String> parts = new ArrayList<>();
        for (Content c : outcome.content()) {
            if (c instanceof Content.Text t) {
                parts.add(t.text());
            } else if (c instanceof Content.Json j) {
                parts.add(json(j.value()));
            } else if (c instanceof Content.Link l) {
                parts.add("[link: " + l.uri() + (l.mimeType() == null ? "" : " (" + l.mimeType() + ")") + "]");
            } else if (c instanceof Content.Data d) {
                parts.add("[" + d.mimeType() + " data, " + d.base64Data().length() + " base64 chars, not shown]");
            } else if (c instanceof Content.Resource r) {
                parts.add(r.text() != null ? "[resource: " + r.uri() + "]\n" + r.text()
                        : "[resource: " + r.uri() + ", " + r.base64Blob().length() + " base64 chars, not shown]");
            } else if (c instanceof Content.Unmapped u) {
                parts.add("[unmapped " + u.protocolType() + " content]");
            }
        }
        if (parts.isEmpty() && outcome.structured() != null) {
            parts.add(json(outcome.structured()));
        }
        return String.join("\n", parts);
    }

    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /** Builds an {@link AnthropicDiscovery}. */
    public static final class Builder {
        private final CapabilityCatalog catalog;
        private DiscoverySession session;
        private CallerIdentity caller;
        private Mode mode = Mode.CATALOG_SEARCH;
        private boolean keepSelectedLoaded;
        private io.github.rahulbsw.protoweft.discovery.ProviderToolNames names;

        private Builder(CapabilityCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        /** Records selections and supplies the caller; must belong to the same catalog. */
        public Builder session(DiscoverySession session) {
            this.session = session;
            return this;
        }

        /** Whose permissions apply. Defaults to the session's caller, else anonymous. */
        public Builder caller(CallerIdentity caller) {
            this.caller = caller;
            return this;
        }

        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /** Send capabilities already selected in the session without {@code defer_loading} (default off). */
        public Builder keepSelectedLoaded(boolean keep) {
            this.keepSelectedLoaded = keep;
            return this;
        }

        /** Shares a name mapping, e.g. with an {@link AnthropicPayloads} of the same conversation. */
        public Builder names(io.github.rahulbsw.protoweft.discovery.ProviderToolNames names) {
            this.names = names;
            return this;
        }

        public AnthropicDiscovery build() {
            if (session != null) {
                if (session.catalog() != catalog) {
                    throw new IllegalArgumentException("session belongs to a different catalog");
                }
                if (caller != null && !caller.equals(session.caller())) {
                    throw new IllegalArgumentException("caller differs from the session's caller");
                }
            }
            if (caller == null) {
                caller = CallerIdentity.ANONYMOUS;
            }
            return new AnthropicDiscovery(this);
        }
    }
}
