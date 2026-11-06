package io.github.rahulbsw.protoweft.discovery.openai;

import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.NamespaceTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseToolSearchCall;
import com.openai.models.responses.ResponseToolSearchOutputItemParam;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolSearchTool;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.CapabilityCard;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoverySession;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.discovery.OpenAiPayloads;
import io.github.rahulbsw.protoweft.discovery.ProviderToolNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * OpenAI tool search (Responses API) for a {@link CapabilityCatalog}, as official OpenAI Java SDK
 * types. Protoweft makes no model calls: put {@link #tools()} on your
 * {@code ResponseCreateParams}, send it with your own {@code OpenAIClient}, and feed the
 * {@link Response} to {@link #nextInput(Response)} to get the input items of the next request.
 *
 * <ul>
 *   <li>{@link Mode#CLIENT_SEARCH} (default): a client-executed {@code tool_search} tool. Each
 *       {@code tool_search_call} is answered by searching the catalog for the caller, with a
 *       {@code tool_search_output} holding the full function definitions of the hits.
 *   <li>{@link Mode#HOSTED}: OpenAI's hosted {@code tool_search} over {@code defer_loading}
 *       functions, optionally grouped per source in {@code namespace}s (deferral stays on the
 *       inner functions).
 * </ul>
 *
 * <p>The wire JSON is the same as {@link OpenAiPayloads}, whose filtering and naming this class
 * reuses, so only capabilities the caller may see and that are available are ever sent. A call
 * to a capability the caller may not see is answered exactly like a call to an unknown one.
 *
 * @see <a href="https://developers.openai.com/api/docs/guides/tools-tool-search">Tool search</a>
 */
public final class OpenAiDiscovery {

    /** Who searches: our catalog (client-executed) or OpenAI (hosted). */
    public enum Mode { CLIENT_SEARCH, HOSTED }

    private final CapabilityCatalog catalog;
    private final DiscoverySession session;
    private final CallerIdentity caller;
    private final Mode mode;
    private final boolean namespaces;
    private final boolean sessionTools;
    private final boolean answerUnknownCalls;
    private final Boolean strict;
    private final ProviderToolNames names;
    private final OpenAiPayloads raw;

    private OpenAiDiscovery(Builder b) {
        this.catalog = b.catalog;
        this.session = b.session;
        this.caller = b.caller != null ? b.caller : b.session != null ? b.session.caller() : CallerIdentity.ANONYMOUS;
        this.mode = b.mode;
        this.namespaces = b.namespaces;
        this.sessionTools = b.sessionTools;
        this.answerUnknownCalls = b.answerUnknownCalls;
        this.strict = b.strict;
        this.names = b.names;
        this.raw = new OpenAiPayloads(catalog, names);
    }

    /** Client-executed search for an anonymous caller, no session. */
    public static OpenAiDiscovery of(CapabilityCatalog catalog) {
        return builder(catalog).build();
    }

    public static Builder builder(CapabilityCatalog catalog) {
        return new Builder(Objects.requireNonNull(catalog, "catalog"), null);
    }

    /** Searches and calls for the session's caller, recording selections in the session. */
    public static Builder builder(DiscoverySession session) {
        Objects.requireNonNull(session, "session");
        return new Builder(session.catalog(), session);
    }

    public CapabilityCatalog catalog() {
        return catalog;
    }

    public Optional<DiscoverySession> session() {
        return Optional.ofNullable(session);
    }

    public CallerIdentity caller() {
        return caller;
    }

    public Mode mode() {
        return mode;
    }

    /** The name mapping shared with {@link #payloads()}. */
    public ProviderToolNames names() {
        return names;
    }

    /** The raw-JSON equivalent of this adapter, sharing its name mapping. */
    public OpenAiPayloads payloads() {
        return raw;
    }

    // ------------------------------------------------------------------ request tools

    /** The default filter: everything the configured caller may see and that is available. */
    public DiscoveryQuery defaultFilter() {
        return DiscoveryQuery.builder("").caller(caller).build();
    }

    /** {@link #tools(DiscoveryQuery)} with {@link #defaultFilter()}. */
    public List<Tool> tools() {
        return tools(defaultFilter());
    }

    /**
     * The {@code tools} of a request. {@link Mode#CLIENT_SEARCH}: the client search tool, plus
     * the session's selected capabilities as normal functions when session tools are enabled.
     * {@link Mode#HOSTED}: the hosted search tool and every capability the filter allows as a
     * deferred function. The filter's caller decides what is visible.
     */
    public List<Tool> tools(DiscoveryQuery filter) {
        Objects.requireNonNull(filter, "filter");
        List<Tool> out = new ArrayList<>();
        if (mode == Mode.CLIENT_SEARCH) {
            out.add(Tool.ofSearch(clientSearchTool()));
            if (sessionTools && session != null) {
                Set<String> permitted = new LinkedHashSet<>();
                permitted(filter).forEach(s -> permitted.add(s.name()));
                session.loadedSchemas().stream().filter(s -> permitted.contains(s.name()))
                        .forEach(s -> out.add(Tool.ofFunction(function(s, false))));
            }
        } else {
            out.add(Tool.ofSearch(hostedSearchTool()));
            out.addAll(deferredTools(filter));
        }
        return out;
    }

    /** The client-executed search tool: the model asks, the catalog answers. */
    public ToolSearchTool clientSearchTool() {
        Map<String, Object> rawTool = raw.clientSearchTool();
        return ToolSearchTool.builder()
                .execution(ToolSearchTool.Execution.CLIENT)
                .description((String) rawTool.get("description"))
                .parameters(JsonValue.from(rawTool.get("parameters")))
                .build();
    }

    /** OpenAI's hosted search over deferred functions: {@code {"type":"tool_search"}}. */
    public static ToolSearchTool hostedSearchTool() {
        return ToolSearchTool.builder().build();
    }

    /**
     * Every capability the filter allows as a {@code defer_loading} function, or, with
     * namespaces, as one {@code namespace} per source with deferral on the inner functions.
     */
    public List<Tool> deferredTools(DiscoveryQuery filter) {
        List<Tool> out = new ArrayList<>();
        if (!namespaces) {
            permitted(filter).forEach(s -> out.add(Tool.ofFunction(function(s, true))));
            return out;
        }
        for (Map<String, Object> ns : raw.deferredFunctions(filter, true)) {
            NamespaceTool.Builder group = NamespaceTool.builder()
                    .name((String) ns.get("name"))
                    .description((String) ns.get("description"));
            for (Object fn : (List<?>) ns.get("tools")) {
                specOf((String) ((Map<?, ?>) fn).get("name"))
                        .ifPresent(spec -> group.addTool(namespacedFunction(spec)));
            }
            out.add(Tool.ofNamespace(group.build()));
        }
        return out;
    }

    /** A function definition for a capability. */
    // The SDK requires strict to be set; JsonMissing (a raw JsonField in Java) omits it from the wire.
    @SuppressWarnings("unchecked")
    public FunctionTool function(ToolSpec spec, boolean deferred) {
        Map<String, Object> fn = raw.function(spec, deferred);
        FunctionTool.Builder b = FunctionTool.builder()
                .name((String) fn.get("name"))
                .description(spec.description())
                .parameters(parameters(fn));
        if (strict == null) {
            b.strict(JsonMissing.of());
        } else {
            b.strict(strict);
        }
        if (deferred) {
            b.deferLoading(true);
        }
        return b.build();
    }

    private NamespaceTool.Tool.Function namespacedFunction(ToolSpec spec) {
        Map<String, Object> fn = raw.function(spec, true);
        NamespaceTool.Tool.Function.Builder b = NamespaceTool.Tool.Function.builder()
                .name((String) fn.get("name"))
                .description(spec.description())
                .parameters(JsonValue.from(fn.get("parameters")))
                .deferLoading(true);
        if (strict != null) {
            b.strict(strict);
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static FunctionTool.Parameters parameters(Map<String, Object> fn) {
        return FunctionTool.Parameters.builder()
                .putAllAdditionalProperties(SdkJson.values((Map<String, Object>) fn.get("parameters")))
                .build();
    }

    /** Full specs the filter allows, in the same order and with the same filters as the raw payloads. */
    private List<ToolSpec> permitted(DiscoveryQuery filter) {
        List<ToolSpec> out = new ArrayList<>();
        for (Map<String, Object> fn : raw.deferredFunctions(filter, false)) {
            specOf((String) fn.get("name")).ifPresent(out::add);
        }
        return out;
    }

    private Optional<ToolSpec> specOf(String providerName) {
        return names.catalogName(providerName).flatMap(catalog::schema);
    }

    // ------------------------------------------------------------------ answering the model

    /**
     * A {@code tool_search_output} loading the given capabilities as full, non-deferred function
     * definitions. Names that don't exist or that the caller may not see are skipped; with a
     * session, the loaded capabilities are recorded as selected.
     */
    public ResponseToolSearchOutputItemParam toolSearchOutput(String callId, List<String> catalogNames) {
        Objects.requireNonNull(callId, "callId");
        List<ToolSpec> specs = session != null
                ? session.select(catalogNames)
                : catalogNames.stream().filter(n -> catalog.visible(n, caller))
                        .map(n -> catalog.schema(n).orElse(null)).filter(Objects::nonNull).toList();
        ResponseToolSearchOutputItemParam.Builder out = ResponseToolSearchOutputItemParam.builder()
                .execution(ResponseToolSearchOutputItemParam.Execution.CLIENT)
                .callId(callId)
                .status(ResponseToolSearchOutputItemParam.Status.COMPLETED)
                .tools(List.of());
        specs.forEach(spec -> out.addTool(function(spec, false)));
        return out.build();
    }

    /**
     * Answers a client {@code tool_search_call} by searching the catalog for the caller.
     * Arguments that aren't a valid search (no {@code query}) load no tools.
     */
    public ResponseToolSearchOutputItemParam searchOutput(String callId, Map<String, Object> arguments) {
        DiscoveryQuery query;
        try {
            query = DiscoveryTools.query(arguments, caller);
        } catch (ProtoweftException e) {
            return toolSearchOutput(callId, List.of());
        }
        return toolSearchOutput(callId, catalog.search(query).names());
    }

    /**
     * Runs a function call against the catalog and returns its {@code function_call_output}.
     * Unknown and forbidden names get the same error text; failures are reported as text
     * starting with {@code Error:}.
     */
    public ResponseInputItem.FunctionCallOutput functionCallOutput(ResponseFunctionToolCall call) {
        return ResponseInputItem.FunctionCallOutput.builder()
                .callId(call.callId())
                .output(run(call.name(), call.arguments()))
                .build();
    }

    /**
     * The input item answering one output item: a {@code tool_search_output} for a client
     * {@code tool_search_call}, a {@code function_call_output} for a function call, and nothing
     * for anything else (messages, reasoning, hosted search steps). With
     * {@link Builder#answerUnknownCalls(boolean) answerUnknownCalls(false)}, calls to names that
     * aren't catalog capabilities are left for the application.
     */
    public Optional<ResponseInputItem> handle(ResponseOutputItem item) {
        if (item.isToolSearchCall()) {
            ResponseToolSearchCall call = item.asToolSearchCall();
            if (!ResponseToolSearchCall.Execution.CLIENT.equals(call.execution()) || call.callId().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(ResponseInputItem.ofToolSearchOutput(
                    searchOutput(call.callId().get(), SdkJson.object(call._arguments()))));
        }
        if (item.isFunctionCall()) {
            ResponseFunctionToolCall call = item.asFunctionCall();
            if (!answerUnknownCalls && resolve(call.name()).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(ResponseInputItem.ofFunctionCallOutput(functionCallOutput(call)));
        }
        return Optional.empty();
    }

    /**
     * The input items answering a whole response, in output order. Send them as the next
     * request's input, with {@code previousResponseId(response.id())} (or after the response's
     * own output items when you keep the history yourself). Empty means nothing to answer.
     */
    public List<ResponseInputItem> nextInput(Response response) {
        List<ResponseInputItem> out = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            handle(item).ifPresent(out::add);
        }
        return out;
    }

    /** The catalog name behind a provider tool name, if this adapter can map it. */
    public Optional<String> catalogName(String providerName) {
        return resolve(providerName);
    }

    /**
     * Maps a provider name back, registering the catalog's current names first if needed (for
     * example when the request was built by another instance).
     */
    private Optional<String> resolve(String providerName) {
        Optional<String> known = names.catalogName(providerName);
        if (known.isPresent()) {
            return known;
        }
        catalog.cards().stream().map(CapabilityCard::name).sorted(Comparator.naturalOrder()).forEach(n -> {
            try {
                names.providerName(n);
            } catch (IllegalStateException collision) {
                // A colliding name is never sent, so it can't be called either.
            }
        });
        return names.catalogName(providerName);
    }

    private String run(String providerName, String argumentsJson) {
        String unknown = "Error: unknown tool: " + providerName;
        Optional<String> name = resolve(providerName);
        if (name.isEmpty() || !catalog.visible(name.get(), caller)) {
            return unknown;
        }
        Map<String, Object> arguments;
        try {
            arguments = SdkJson.object(argumentsJson);
        } catch (IllegalArgumentException e) {
            return "Error: arguments must be a JSON object: " + e.getMessage();
        }
        if (session != null) {
            session.select(List.of(name.get()));
        }
        try {
            return OutcomeText.of(catalog.invoke(name.get(), arguments, caller));
        } catch (ProtoweftException e) {
            return e.category() == ErrorCategory.TOOL_NOT_FOUND ? unknown : "Error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Error: " + providerName + " failed: " + e.getMessage();
        }
    }

    /** Builds an {@link OpenAiDiscovery}. */
    public static final class Builder {
        private final CapabilityCatalog catalog;
        private final DiscoverySession session;
        private CallerIdentity caller;
        private Mode mode = Mode.CLIENT_SEARCH;
        private boolean namespaces;
        private boolean sessionTools;
        private boolean answerUnknownCalls = true;
        private Boolean strict;
        private ProviderToolNames names = new ProviderToolNames();

        private Builder(CapabilityCatalog catalog, DiscoverySession session) {
            this.catalog = catalog;
            this.session = session;
        }

        /** Who searches and calls (default: the session's caller, else anonymous). */
        public Builder caller(CallerIdentity caller) {
            Objects.requireNonNull(caller, "caller");
            if (session != null && !session.caller().equals(caller)) {
                throw new IllegalArgumentException("caller differs from the session's caller");
            }
            this.caller = caller;
            return this;
        }

        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /** In {@link Mode#HOSTED}, group deferred functions per source in namespaces (default off). */
        public Builder namespaces(boolean namespaces) {
            this.namespaces = namespaces;
            return this;
        }

        /**
         * In {@link Mode#CLIENT_SEARCH}, also send the session's selected capabilities as normal
         * functions (default off). Enable it when a request doesn't carry the history whose
         * {@code tool_search_output} items already loaded them.
         */
        public Builder sessionTools(boolean sessionTools) {
            this.sessionTools = sessionTools;
            return this;
        }

        /** Whether function calls to names outside the catalog get an error output (default on). */
        public Builder answerUnknownCalls(boolean answer) {
            this.answerUnknownCalls = answer;
            return this;
        }

        /**
         * The {@code strict} flag on function definitions. {@code null} (default) omits it, as
         * {@link OpenAiPayloads} does, leaving the API's default.
         */
        public Builder strict(Boolean strict) {
            this.strict = strict;
            return this;
        }

        /** Shares a name mapping, for example with an {@link OpenAiPayloads} used elsewhere. */
        public Builder names(ProviderToolNames names) {
            this.names = Objects.requireNonNull(names, "names");
            return this;
        }

        public OpenAiDiscovery build() {
            return new OpenAiDiscovery(this);
        }
    }
}
