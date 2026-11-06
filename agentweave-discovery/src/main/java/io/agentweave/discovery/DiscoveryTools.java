package io.agentweave.discovery;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The generic search-tool fallback: three meta-tools that let any model discover and call
 * capabilities without receiving every schema. Serve it like any {@link ToolSource} — over
 * AgentWeave's MCP server, through LangChain4j, or directly.
 *
 * <ul>
 *   <li>{@code search_capabilities} — concise cards, confidence, fallback stage, hidden counts
 *   <li>{@code describe_capabilities} — full input schemas; marks them loaded in the session
 *   <li>{@code call_capability} — calls a capability by name
 * </ul>
 */
public final class DiscoveryTools implements ToolSource {

    public static final String SEARCH = "search_capabilities";
    public static final String DESCRIBE = "describe_capabilities";
    public static final String CALL = "call_capability";

    private final CapabilityCatalog catalog;
    private final DiscoverySession session;

    private DiscoveryTools(CapabilityCatalog catalog, DiscoverySession session) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.session = session;
    }

    /** Meta-tools without a session: selections aren't remembered across calls. */
    public static DiscoveryTools of(CapabilityCatalog catalog) {
        return new DiscoveryTools(catalog, null);
    }

    /** Meta-tools that record selections in {@code session}. */
    public static DiscoveryTools forSession(DiscoverySession session) {
        return new DiscoveryTools(session.catalog(), session);
    }

    @Override
    public List<ToolSpec> listTools() {
        return List.of(
                new ToolSpec(SEARCH, "Search the available capabilities (tools and agents) by what you need to do. "
                        + "Always search before calling. Returns short cards, not schemas. If confidence is LOW, "
                        + "rephrase or broaden the query.",
                        Payloads.searchSchema()),
                new ToolSpec(DESCRIBE, "Get the full input schemas of capabilities found by " + SEARCH
                        + ". Call this before " + CALL + ".",
                        schema(Map.of("names", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Capability names from search results")), List.of("names"))),
                new ToolSpec(CALL, "Call a capability by name with arguments that match its schema.",
                        schema(Map.of("name", Map.of("type", "string", "description", "Capability name"),
                                "arguments", Map.of("type", "object", "description", "Arguments for the capability")),
                                List.of("name", "arguments"))));
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, session != null ? session.caller() : CallerIdentity.ANONYMOUS);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return invoke(invocation, caller, io.agentweave.core.tool.ToolProgress.NOOP);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller,
                              io.agentweave.core.tool.ToolProgress progress) {
        Map<String, Object> args = invocation.arguments();
        return switch (invocation.toolName()) {
            case SEARCH -> search(args, caller);
            case DESCRIBE -> describe(args, caller);
            case CALL -> call(args, caller, progress);
            default -> throw new AgentWeaveException(ErrorCategory.TOOL_NOT_FOUND, CapabilityCatalog.DISCOVERY,
                    "unknown tool: " + invocation.toolName());
        };
    }

    /**
     * Parses {@code search_capabilities} arguments into a query for {@code caller}: {@code query}
     * and {@code limit} only. Other keys are ignored; protocol, source and hint filters are the
     * application's to set on a {@link DiscoveryQuery}.
     */
    public static DiscoveryQuery query(Map<String, Object> args, CallerIdentity caller) {
        Object text = args.get("query");
        if (!(text instanceof String s) || s.isBlank()) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, CapabilityCatalog.DISCOVERY,
                    "'query' must be a non-empty string");
        }
        DiscoveryQuery.Builder q = DiscoveryQuery.builder(s).caller(caller);
        if (args.get("limit") instanceof Number n) {
            q.limit(Math.max(1, Math.min(50, n.intValue())));
        }
        return q.build();
    }

    private ToolOutcome search(Map<String, Object> args, CallerIdentity caller) {
        DiscoveryResult result = catalog.search(query(args, caller));
        return new ToolOutcome(List.of(new Content.Text(render(result))), false, structured(result));
    }

    private ToolOutcome describe(Map<String, Object> args, CallerIdentity caller) {
        List<String> names = strings(args.get("names"));
        if (names.isEmpty()) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, CapabilityCatalog.DISCOVERY,
                    "'names' must list at least one capability");
        }
        List<ToolSpec> specs = session != null
                ? session.select(names)
                : names.stream().filter(n -> catalog.visible(n, caller))
                        .map(n -> catalog.schema(n).orElse(null)).filter(Objects::nonNull).toList();
        List<Map<String, Object>> described = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (ToolSpec spec : specs) {
            described.add(Map.of("name", spec.name(), "description", spec.description(), "input_schema", spec.inputSchema()));
            text.append(spec.name()).append(": ").append(spec.description()).append('\n')
                    .append("  input_schema: ").append(CanonicalJson.write(spec.inputSchema())).append('\n');
        }
        List<String> missing = names.stream().filter(n -> specs.stream().noneMatch(s -> s.name().equals(n))).toList();
        if (!missing.isEmpty()) {
            text.append("Not found: ").append(String.join(", ", missing)).append('\n');
        }
        return new ToolOutcome(List.of(new Content.Text(text.toString().strip())), false,
                Map.of("capabilities", described, "not_found", missing));
    }

    private ToolOutcome call(Map<String, Object> args, CallerIdentity caller,
                             io.agentweave.core.tool.ToolProgress progress) {
        if (!(args.get("name") instanceof String name)) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, CapabilityCatalog.DISCOVERY,
                    "'name' must be a string");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (args.get("arguments") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> arguments.put(String.valueOf(k), v));
        } else if (args.get("arguments") != null) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, CapabilityCatalog.DISCOVERY,
                    "'arguments' must be an object");
        }
        return catalog.invoke(name, arguments, caller, progress);
    }

    static String render(DiscoveryResult r) {
        StringBuilder out = new StringBuilder();
        for (DiscoveryResult.Hit h : r.hits()) {
            out.append(cardLine(h.card())).append('\n');
        }
        if (r.hits().isEmpty()) {
            out.append("No capabilities matched this query; try different words for the task.\n");
        }
        if (!r.namesOnly().isEmpty()) {
            out.append("Other candidates (search or describe them by name):\n");
            r.namesOnly().forEach(c -> out.append("  ").append(c.name()).append(" — ").append(c.summary()).append('\n'));
        }
        out.append("confidence: ").append(r.confidence().level()).append("; fallback: ").append(r.stage())
                .append("; mode: ").append(r.mode());
        if (!r.hiddenUnavailable().isEmpty()) {
            out.append("; hidden as unavailable: ").append(r.hiddenUnavailable());
        }
        return out.toString();
    }

    private static String cardLine(CapabilityCard c) {
        return "- " + c.name() + " [" + c.protocol() + "] " + c.summary()
                + (c.parameters().isEmpty() ? "" : " (params: " + c.parameterList() + ")");
    }

    private static Map<String, Object> structured(DiscoveryResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> hits = new ArrayList<>();
        for (DiscoveryResult.Hit h : r.hits()) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("name", h.card().name());
            hit.put("summary", h.card().summary());
            hit.put("protocol", h.card().protocol().value());
            hit.put("parameters", h.card().parameters().stream().map(CapabilityCard.Parameter::name).toList());
            hits.add(hit);
        }
        out.put("capabilities", hits);
        out.put("other_candidates", r.namesOnly().stream().map(CapabilityCard::name).toList());
        out.put("confidence", r.confidence().level().name());
        out.put("fallback_stage", r.stage().name());
        out.put("mode", r.mode().name());
        out.put("hidden_unavailable", r.hiddenUnavailable());
        return out;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return value instanceof String s ? List.of(s) : List.of();
    }
}
