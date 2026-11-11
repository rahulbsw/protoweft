package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns ARGI's pending tool approvals into an {@code input-required} question, and the caller's
 * reply into {@link ToolFeedback} decisions.
 *
 * <p>Replies: text {@code approve}/{@code yes} or {@code reject [reason]}/{@code no} decides every
 * undecided call; a JSON data part {@code {"decision":"approved|rejected|edited","id":"<tool call
 * id>","arguments":{...},"reason":"..."}}, a list of them, or {@code {"decisions":[...]}} decides
 * calls one by one ({@code id} may be omitted when one call is undecided; {@code name} selects by
 * tool name when it is unique).
 */
final class ToolApprovals {

    static final String DEFAULT_REJECTION = "The caller rejected this tool call.";

    private static final Set<String> APPROVE = Set.of("approve", "approved", "yes", "y", "ok", "allow");
    private static final Set<String> REJECT = Set.of("reject", "rejected", "no", "n", "deny", "denied");
    private static final Set<String> EDIT = Set.of("edit", "edited");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolApprovals() {
    }

    /** The outcome of reading a reply: the decisions so far, or why the reply was not understood. */
    record Parsed(Map<String, ToolFeedback> decided, String error) {
    }

    /** The question listing the undecided calls (name, id, arguments, description) and how to answer. */
    static String question(List<ToolFeedback> pending, Map<String, ToolFeedback> decided, String problem) {
        List<ToolFeedback> open = pending.stream().filter(f -> !decided.containsKey(f.getId())).toList();
        StringBuilder q = new StringBuilder();
        if (problem != null) {
            q.append(problem).append("\n\n");
        }
        q.append(open.size() == 1 ? "The agent wants to call a tool that needs approval:"
                : "The agent wants to call " + open.size() + " tools that need approval:");
        int i = 1;
        for (ToolFeedback f : open) {
            q.append('\n').append(i++).append(". ").append(f.getName()).append(" (id ").append(f.getId()).append(')');
            q.append("\n   Arguments: ").append(f.getArguments());
            if (f.getDescription() != null && !f.getDescription().isBlank()) {
                q.append("\n   ").append(f.getDescription().strip().replace("\n", "\n   "));
            }
        }
        q.append("\n\nReply \"approve\" or \"reject <reason>\"").append(open.size() > 1 ? " for all of them" : "")
                .append(", or send a JSON data part {\"decision\":\"approved|rejected|edited\",\"id\":\"<tool call id>\","
                        + "\"arguments\":{...}}").append(open.size() > 1 ? " per tool call." : ".");
        return q.toString();
    }

    /**
     * Metadata key under which a request resumed over AG-UI carries its resume entries
     * ({@code interruptId}, {@code status}, {@code payload}); see {@code protoweft-agui}'s {@code AgUi}.
     */
    static final String RESUME_METADATA = "agui/resume";

    /**
     * A reply resumed over AG-UI, rewritten as one JSON decision per resume entry (the interrupt id
     * is the tool call id): an object payload is the decision itself, a text payload is read like a
     * text reply ("approve", "reject because ..."), an abandoned interrupt is a rejection. Other
     * replies are returned unchanged.
     */
    static AgentMessage fromResumeEntries(AgentMessage reply) {
        if (!(reply.metadata().get(RESUME_METADATA) instanceof List<?> entries)) {
            return reply;
        }
        List<Content> parts = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e) || !(e.get("interruptId") instanceof String id)) {
                continue;
            }
            Map<String, Object> decision = new LinkedHashMap<>();
            if ("cancelled".equals(e.get("status"))) {
                decision.put("decision", "rejected");
                decision.put("reason", "The user abandoned this approval.");
            } else if (e.get("payload") instanceof Map<?, ?> payload) {
                payload.forEach((k, v) -> decision.put(String.valueOf(k), v));
            } else if (e.get("payload") instanceof String text && !text.isBlank()) {
                String[] words = text.strip().split("\\s+", 2);
                String word = words[0].toLowerCase(java.util.Locale.ROOT);
                decision.put("decision", APPROVE.contains(word) ? "approved" : REJECT.contains(word) ? "rejected" : word);
                if (words.length > 1 && REJECT.contains(word)) {
                    decision.put("reason", words[1]);
                }
            }
            decision.put("id", id);
            parts.add(new Content.Json(decision));
        }
        return new AgentMessage(reply.role(), parts, reply.messageId(), reply.taskId(), reply.contextId(), null);
    }

    /** Reads the caller's reply into decisions for the pending calls, on top of earlier ones. */
    static Parsed parse(AgentMessage reply, List<ToolFeedback> pending, Map<String, ToolFeedback> earlier) {
        Map<String, ToolFeedback> decided = new LinkedHashMap<>(earlier);
        List<Map<?, ?>> objects = new ArrayList<>();
        for (Content part : reply.parts()) {
            if (part instanceof Content.Json json) {
                collect(json.value(), objects);
            }
        }
        if (!objects.isEmpty()) {
            for (Map<?, ?> object : objects) {
                String error = applyJson(object, pending, decided);
                if (error != null) {
                    return new Parsed(earlier, error);
                }
            }
            return new Parsed(decided, null);
        }
        String error = applyText(reply.text(), pending, decided);
        return error != null ? new Parsed(earlier, error) : new Parsed(decided, null);
    }

    private static void collect(Object value, List<Map<?, ?>> out) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("decisions") instanceof List<?> list) {
                collect(list, out);
            } else {
                out.add(map);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collect(item, out);
            }
        }
    }

    private static String applyText(String text, List<ToolFeedback> pending, Map<String, ToolFeedback> decided) {
        String trimmed = text == null ? "" : text.strip();
        String[] words = trimmed.split("\\s+", 2);
        String verb = words[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        FeedbackResult result = APPROVE.contains(verb) ? FeedbackResult.APPROVED
                : REJECT.contains(verb) ? FeedbackResult.REJECTED : null;
        if (result == null) {
            return "The reply was not understood.";
        }
        String reason = words.length > 1 && !words[1].isBlank() ? words[1].strip() : DEFAULT_REJECTION;
        for (ToolFeedback f : pending) {
            if (!decided.containsKey(f.getId())) {
                decided.put(f.getId(), decide(f, result, null, reason));
            }
        }
        return null;
    }

    private static String applyJson(Map<?, ?> object, List<ToolFeedback> pending, Map<String, ToolFeedback> decided) {
        String decision = string(object.get("decision"));
        String verb = decision == null ? "" : decision.toLowerCase(Locale.ROOT);
        FeedbackResult result = APPROVE.contains(verb) ? FeedbackResult.APPROVED
                : REJECT.contains(verb) ? FeedbackResult.REJECTED
                : EDIT.contains(verb) ? FeedbackResult.EDITED : null;
        if (result == null) {
            return "Unknown decision '" + decision + "': use approved, rejected or edited.";
        }
        List<ToolFeedback> targets = targets(object, pending, decided);
        if (targets.isEmpty()) {
            Object id = object.get("id") != null ? object.get("id") : object.get("name");
            return id != null ? "No pending tool call matches '" + id + "'."
                    : "Several tool calls are pending: give the \"id\" of the one this decision is for.";
        }
        String arguments = null;
        if (result == FeedbackResult.EDITED) {
            Object args = object.get("arguments");
            if (args == null) {
                return "An edited decision needs \"arguments\".";
            }
            if (targets.size() > 1) {
                return "Several tool calls are pending: give the \"id\" of the one to edit.";
            }
            arguments = args instanceof String s ? s : JSON.writeValueAsString(args);
        }
        String reason = string(object.get("reason"));
        for (ToolFeedback f : targets) {
            decided.put(f.getId(), decide(f, result, arguments, reason == null ? DEFAULT_REJECTION : reason));
        }
        return null;
    }

    /** The calls a JSON decision is for: by id, else by unique name, else the only (or every) undecided call. */
    private static List<ToolFeedback> targets(Map<?, ?> object, List<ToolFeedback> pending,
                                              Map<String, ToolFeedback> decided) {
        String id = string(object.get("id"));
        if (id != null) {
            return pending.stream().filter(f -> id.equals(f.getId())).toList();
        }
        String name = string(object.get("name"));
        if (name != null) {
            List<ToolFeedback> named = pending.stream().filter(f -> name.equals(f.getName())).toList();
            return named.size() == 1 ? named : List.of();
        }
        List<ToolFeedback> open = pending.stream().filter(f -> !decided.containsKey(f.getId())).toList();
        return open.size() == 1 || object.get("arguments") == null ? open : List.of();
    }

    private static ToolFeedback decide(ToolFeedback pending, FeedbackResult result, String arguments, String reason) {
        ToolFeedback.Builder b = ToolFeedback.builder(pending).result(result);
        if (result == FeedbackResult.EDITED) {
            b.arguments(arguments);
        } else if (result == FeedbackResult.REJECTED) {
            // ARGI's HumanInTheLoopHook tells the model the description as the rejection reason.
            b.description(reason);
        }
        return b.build();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
