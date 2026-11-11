package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiMessage.ToolMessage;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Offers Protoweft tools (local, MCP, UTCP, RAP, A2A through a registry) to a remote AG-UI agent as
 * the application's own tools, and runs the calls the agent leaves pending.
 *
 * <p>Consent (spec "Tool calls", security): a call runs only if its tool is marked
 * {@code readOnly}, or the {@link ToolApproval} approves it. Otherwise the agent is told the call
 * needs approval and nothing runs. Arguments are model output: they must parse as a JSON object and
 * pass a structural check against the tool's schema (required members, member types, enums) before
 * anything runs; the tool's own validation applies after that.
 */
final class ToolBridge {

    /** AG-UI tool names: what common model providers accept for function names. */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ToolSource source;
    private final ToolApproval approval;
    private final CallerIdentity caller;
    /** Offered name → tool spec. */
    private final Map<String, ToolSpec> offered = new LinkedHashMap<>();

    ToolBridge(ToolSource source, Predicate<ToolSpec> filter, ToolApproval approval, CallerIdentity caller) {
        this.source = source;
        this.approval = approval;
        this.caller = caller;
        for (ToolSpec spec : source.listTools()) {
            if (filter.test(spec)) {
                String name = agUiName(spec.name());
                if (offered.putIfAbsent(name, spec) != null) {
                    throw new IllegalArgumentException("tools '" + offered.get(name).name() + "' and '" + spec.name()
                            + "' map to the same AG-UI tool name '" + name + "'");
                }
            }
        }
    }

    /**
     * A tool name every common model provider accepts: registry separators ({@code .}) become
     * {@code __}, other characters outside {@code [A-Za-z0-9_-]} become {@code _}, at most 64 chars.
     */
    static String agUiName(String name) {
        String s = name.replace(".", "__").replaceAll("[^A-Za-z0-9_-]", "_");
        if (s.length() > 64) {
            s = s.substring(0, 55) + "_" + Integer.toHexString(name.hashCode());
        }
        if (!NAME.matcher(s).matches()) {
            throw new IllegalArgumentException("cannot derive an AG-UI tool name from '" + name + "'");
        }
        return s;
    }

    List<RunAgentInput.Tool> tools() {
        List<RunAgentInput.Tool> out = new ArrayList<>();
        offered.forEach((name, spec) -> out.add(new RunAgentInput.Tool(name, spec.description(), spec.inputSchema(),
                null)));
        return out;
    }

    boolean offers(String toolName) {
        return offered.containsKey(toolName);
    }

    /** Runs one pending call and returns its answer. */
    ToolMessage run(String toolCallId, String toolName, String arguments) {
        ToolSpec spec = offered.get(toolName);
        Map<String, Object> args;
        try {
            args = parseArguments(arguments);
            String problem = SchemaCheck.problem(spec.inputSchema(), args);
            if (problem != null) {
                return failure(toolCallId, "invalid arguments: " + problem);
            }
        } catch (AgUiFormatException e) {
            return failure(toolCallId, "invalid arguments: " + e.getMessage());
        }
        ToolApproval.Request request = new ToolApproval.Request(toolCallId, spec, args);
        boolean readOnly = Boolean.TRUE.equals(spec.metadata().get(ToolSpec.META_READ_ONLY));
        if (!readOnly) {
            if (approval == null) {
                return failure(toolCallId, "approval required: '" + spec.name()
                        + "' may change something and the application has no approval step; it did not run");
            }
            if (!approval.approve(request)) {
                return failure(toolCallId, "the user declined this call; it did not run");
            }
        }
        ToolOutcome outcome;
        try {
            outcome = source.invoke(new ToolInvocation(spec.name(), args), caller).dropSubscription();
        } catch (RuntimeException e) {
            return failure(toolCallId, "the tool failed: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()));
        }
        Object content = Contents.toAgUi(outcome.content());
        if (content instanceof String s && s.isEmpty() && outcome.structured() != null) {
            content = Json.write(outcome.structured());
        }
        String error = outcome.error() ? Contents.text(outcome.content()) : null;
        return new ToolMessage(UUID.randomUUID().toString(), content, toolCallId,
                error == null ? null : error.isEmpty() ? "the tool reported an error" : error, null, null, null);
    }

    static ToolMessage failure(String toolCallId, String why) {
        return new ToolMessage(UUID.randomUUID().toString(), why, toolCallId, why, null, null, null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        Object parsed = Json.parse(arguments);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new AgUiFormatException("arguments must be a JSON object");
        }
        return (Map<String, Object>) m;
    }
}
