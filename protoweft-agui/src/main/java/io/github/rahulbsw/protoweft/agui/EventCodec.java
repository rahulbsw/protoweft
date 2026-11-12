package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.rahulbsw.protoweft.agui.Codec.put;
import static io.github.rahulbsw.protoweft.agui.Codec.with;

/** JSON mapping of the 31 event types, following the official schema (see {@link Codec}). */
final class EventCodec {

    private static final Set<String> BASE = Set.of("type", "timestamp", "rawEvent", "metadata");
    private static final Set<String> ATTRIBUTABLE = with(BASE, "subagentRunId");
    private static final Set<String> ENCRYPTED_SUBTYPES = Set.of("message", "tool-call");

    private EventCodec() {
    }

    // ---- encoding -----------------------------------------------------------------------

    static Map<String, Object> encode(AgUiEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", event.type());
        if (event instanceof RunStarted e) {
            m.put("threadId", e.threadId());
            m.put("runId", e.runId());
            put(m, "protocolVersion", e.protocolVersion());
            put(m, "parentRunId", e.parentRunId());
            put(m, "input", e.input() == null ? null : Codec.input(e.input()));
        } else if (event instanceof RunFinished e) {
            m.put("threadId", e.threadId());
            m.put("runId", e.runId());
            put(m, "result", e.result());
            put(m, "outcome", e.outcome() == null ? null : outcome(e.outcome()));
            put(m, "usage", usage(e.usage()));
        } else if (event instanceof RunError e) {
            m.put("message", e.message());
            put(m, "code", e.code());
            put(m, "usage", usage(e.usage()));
        } else if (event instanceof StepStarted e) {
            m.put("stepName", e.stepName());
        } else if (event instanceof StepFinished e) {
            m.put("stepName", e.stepName());
        } else if (event instanceof TextMessageStart e) {
            m.put("messageId", e.messageId());
            put(m, "role", e.role());
            put(m, "name", e.name());
        } else if (event instanceof TextMessageContent e) {
            m.put("messageId", e.messageId());
            m.put("delta", e.delta());
        } else if (event instanceof TextMessageEnd e) {
            m.put("messageId", e.messageId());
        } else if (event instanceof TextMessageChunk e) {
            put(m, "messageId", e.messageId());
            put(m, "role", e.role());
            put(m, "delta", e.delta());
            put(m, "name", e.name());
        } else if (event instanceof ToolCallStart e) {
            m.put("toolCallId", e.toolCallId());
            m.put("toolCallName", e.toolCallName());
            put(m, "parentMessageId", e.parentMessageId());
        } else if (event instanceof ToolCallArgs e) {
            m.put("toolCallId", e.toolCallId());
            m.put("delta", e.delta());
        } else if (event instanceof ToolCallEnd e) {
            m.put("toolCallId", e.toolCallId());
        } else if (event instanceof ToolCallChunk e) {
            put(m, "toolCallId", e.toolCallId());
            put(m, "toolCallName", e.toolCallName());
            put(m, "parentMessageId", e.parentMessageId());
            put(m, "delta", e.delta());
        } else if (event instanceof ToolCallResult e) {
            m.put("messageId", e.messageId());
            m.put("toolCallId", e.toolCallId());
            m.put("content", Codec.content(e.content()));
        } else if (event instanceof ReasoningStart e) {
            m.put("messageId", e.messageId());
        } else if (event instanceof ReasoningEnd e) {
            m.put("messageId", e.messageId());
        } else if (event instanceof ReasoningMessageStart e) {
            m.put("messageId", e.messageId());
            m.put("role", "reasoning");
        } else if (event instanceof ReasoningMessageContent e) {
            m.put("messageId", e.messageId());
            m.put("delta", e.delta());
        } else if (event instanceof ReasoningMessageEnd e) {
            m.put("messageId", e.messageId());
        } else if (event instanceof ReasoningMessageChunk e) {
            put(m, "messageId", e.messageId());
            put(m, "delta", e.delta());
        } else if (event instanceof ReasoningEncryptedValue e) {
            m.put("subtype", e.subtype());
            m.put("entityId", e.entityId());
            m.put("encryptedValue", e.encryptedValue());
        } else if (event instanceof StateSnapshot e) {
            m.put("snapshot", e.snapshot());
        } else if (event instanceof StateDelta e) {
            m.put("delta", e.delta());
        } else if (event instanceof MessagesSnapshot e) {
            m.put("messages", e.messages().stream().map(Codec::message).toList());
        } else if (event instanceof ActivitySnapshot e) {
            m.put("messageId", e.messageId());
            m.put("activityType", e.activityType());
            m.put("content", e.content());
            put(m, "replace", e.replace());
        } else if (event instanceof ActivityDelta e) {
            m.put("messageId", e.messageId());
            m.put("activityType", e.activityType());
            m.put("patch", e.patch());
        } else if (event instanceof SubagentStarted e) {
            m.put("subagentRunId", e.subagentRunId());
            m.put("name", e.name());
            put(m, "description", e.description());
            put(m, "parentSubagentRunId", e.parentSubagentRunId());
            put(m, "parentToolCallId", e.parentToolCallId());
            put(m, "parentMessageId", e.parentMessageId());
        } else if (event instanceof SubagentFinished e) {
            m.put("subagentRunId", e.subagentRunId());
            put(m, "result", e.result());
            put(m, "outcome", e.outcome() == null ? null : subagentOutcome(e.outcome()));
        } else if (event instanceof SubagentError e) {
            m.put("subagentRunId", e.subagentRunId());
            m.put("message", e.message());
            put(m, "code", e.code());
        } else if (event instanceof Raw e) {
            m.put("event", e.event());
            put(m, "source", e.source());
        } else if (event instanceof Custom e) {
            m.put("name", e.name());
            m.put("value", e.value());
        }
        Envelope env = event.envelope() == null ? Envelope.NONE : event.envelope();
        put(m, "timestamp", env.timestamp());
        put(m, "rawEvent", env.rawEvent());
        put(m, "metadata", env.metadata());
        if (attributable(event.type())) {
            put(m, "subagentRunId", env.subagentRunId());
        }
        return m;
    }

    static boolean attributable(String type) {
        return !type.startsWith("RUN_") && !type.equals("MESSAGES_SNAPSHOT") && !type.startsWith("SUBAGENT_");
    }

    private static Map<String, Object> outcome(RunOutcome outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (outcome instanceof RunOutcome.Success s) {
            m.put("type", "success");
            put(m, "pendingToolCallIds", s.pendingToolCallIds());
        } else if (outcome instanceof RunOutcome.Interrupted i) {
            m.put("type", "interrupt");
            m.put("interrupts", i.interrupts().stream().map(Codec::interrupt).toList());
        } else if (outcome instanceof RunOutcome.Cancelled c) {
            m.put("type", "cancelled");
        }
        return m;
    }

    private static Map<String, Object> subagentOutcome(SubagentOutcome outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (outcome instanceof SubagentOutcome.Suspended s) {
            m.put("type", "suspended");
            put(m, "interruptIds", s.interruptIds());
        } else {
            m.put("type", "success");
        }
        return m;
    }

    private static List<Map<String, Object>> usage(List<TokenUsage> usage) {
        return usage == null ? null : usage.stream().map(Codec::usage).toList();
    }

    // ---- decoding -----------------------------------------------------------------------

    /**
     * One event, or {@code null} when it is dropped with a warning (a type this version does not
     * know, or an unrecognised value in a required union position).
     *
     * @throws AgUiFormatException if a field the schema describes carries a value it rejects
     */
    static AgUiEvent decode(Decoder d, Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new AgUiFormatException("an event must be a JSON object");
        }
        if (!(raw.get("type") instanceof String type)) {
            throw new AgUiFormatException("event.type must be a string");
        }
        String path = type;
        return switch (type) {
            case "RUN_STARTED" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "threadId", "runId", "protocolVersion",
                        "parentRunId", "input"));
                Object input = o.optional("input");
                yield new RunStarted(o.string("threadId"), o.string("runId"), o.optString("protocolVersion"),
                        o.optString("parentRunId"), input == null ? null : Codec.input(d, input, path + ".input"),
                        envelope(o, false));
            }
            case "RUN_FINISHED" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "threadId", "runId", "result", "outcome", "usage"));
                yield new RunFinished(o.string("threadId"), o.string("runId"), o.optional("result"),
                        runOutcome(d, o.optional("outcome"), path + ".outcome"),
                        o.optItems("usage", (item, p) -> Codec.usage(d, item, p)), envelope(o, false));
            }
            case "RUN_ERROR" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "message", "code", "usage"));
                yield new RunError(o.string("message"), o.optString("code"),
                        o.optItems("usage", (item, p) -> Codec.usage(d, item, p)), envelope(o, false));
            }
            case "STEP_STARTED" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "stepName"));
                yield new StepStarted(o.string("stepName"), envelope(o, true));
            }
            case "STEP_FINISHED" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "stepName"));
                yield new StepFinished(o.string("stepName"), envelope(o, true));
            }
            case "TEXT_MESSAGE_START" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "role", "name"));
                yield new TextMessageStart(o.string("messageId"), textRole(o), o.optString("name"), envelope(o, true));
            }
            case "TEXT_MESSAGE_CONTENT" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "delta"));
                yield new TextMessageContent(o.string("messageId"), o.string("delta"), envelope(o, true));
            }
            case "TEXT_MESSAGE_END" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId"));
                yield new TextMessageEnd(o.string("messageId"), envelope(o, true));
            }
            case "TEXT_MESSAGE_CHUNK" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "role", "delta", "name"));
                yield new TextMessageChunk(o.optString("messageId"), textRole(o), o.optString("delta"),
                        o.optString("name"), envelope(o, true));
            }
            case "TOOL_CALL_START" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "toolCallId", "toolCallName",
                        "parentMessageId"));
                yield new ToolCallStart(o.string("toolCallId"), o.string("toolCallName"),
                        o.optString("parentMessageId"), envelope(o, true));
            }
            case "TOOL_CALL_ARGS" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "toolCallId", "delta"));
                yield new ToolCallArgs(o.string("toolCallId"), o.string("delta"), envelope(o, true));
            }
            case "TOOL_CALL_END" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "toolCallId"));
                yield new ToolCallEnd(o.string("toolCallId"), envelope(o, true));
            }
            case "TOOL_CALL_CHUNK" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "toolCallId", "toolCallName",
                        "parentMessageId", "delta"));
                yield new ToolCallChunk(o.optString("toolCallId"), o.optString("toolCallName"),
                        o.optString("parentMessageId"), o.optString("delta"), envelope(o, true));
            }
            case "TOOL_CALL_RESULT" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "toolCallId", "content", "role"));
                String role = o.optString("role");
                if (role != null && !role.equals("tool")) {
                    throw new AgUiFormatException(path + ".role must be \"tool\"");
                }
                yield new ToolCallResult(o.string("messageId"), o.string("toolCallId"), o.stringOrParts("content"),
                        envelope(o, true));
            }
            case "REASONING_START", "REASONING_END", "REASONING_MESSAGE_END" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId"));
                String id = o.string("messageId");
                Envelope env = envelope(o, true);
                yield switch (type) {
                    case "REASONING_START" -> new ReasoningStart(id, env);
                    case "REASONING_END" -> new ReasoningEnd(id, env);
                    default -> new ReasoningMessageEnd(id, env);
                };
            }
            case "REASONING_MESSAGE_START" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "role"));
                if (!"reasoning".equals(o.string("role"))) {
                    throw new AgUiFormatException(path + ".role must be \"reasoning\"");
                }
                yield new ReasoningMessageStart(o.string("messageId"), envelope(o, true));
            }
            case "REASONING_MESSAGE_CONTENT" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "delta"));
                yield new ReasoningMessageContent(o.string("messageId"), o.string("delta"), envelope(o, true));
            }
            case "REASONING_MESSAGE_CHUNK" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "delta"));
                yield new ReasoningMessageChunk(o.optString("messageId"), o.optString("delta"), envelope(o, true));
            }
            case "REASONING_ENCRYPTED_VALUE" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "subtype", "entityId", "encryptedValue"));
                String subtype = o.string("subtype");
                if (!ENCRYPTED_SUBTYPES.contains(subtype)) {
                    d.warn("dropped " + path + ": unrecognised subtype '" + subtype + "'");
                    yield null;
                }
                yield new ReasoningEncryptedValue(subtype, o.string("entityId"), o.string("encryptedValue"),
                        envelope(o, true));
            }
            case "STATE_SNAPSHOT" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "snapshot"));
                yield new StateSnapshot(o.requiredAny("snapshot"), envelope(o, true));
            }
            case "STATE_DELTA" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "delta"));
                yield new StateDelta(JsonPatch.read(d, o.array("delta"), path + ".delta"), envelope(o, true));
            }
            case "MESSAGES_SNAPSHOT" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "messages"));
                yield new MessagesSnapshot(o.items("messages", (item, p) -> Codec.message(d, item, p)),
                        envelope(o, false));
            }
            case "ACTIVITY_SNAPSHOT" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "activityType", "content",
                        "replace"));
                yield new ActivitySnapshot(o.string("messageId"), o.string("activityType"), o.object("content"),
                        o.optBoolean("replace"), envelope(o, true));
            }
            case "ACTIVITY_DELTA" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "messageId", "activityType", "patch"));
                yield new ActivityDelta(o.string("messageId"), o.string("activityType"),
                        JsonPatch.read(d, o.array("patch"), path + ".patch"), envelope(o, true));
            }
            case "SUBAGENT_STARTED" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "subagentRunId", "name", "description",
                        "parentSubagentRunId", "parentToolCallId", "parentMessageId"));
                yield new SubagentStarted(o.string("subagentRunId"), o.string("name"), o.optString("description"),
                        o.optString("parentSubagentRunId"), o.optString("parentToolCallId"),
                        o.optString("parentMessageId"), envelope(o, false));
            }
            case "SUBAGENT_FINISHED" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "subagentRunId", "result", "outcome"));
                yield new SubagentFinished(o.string("subagentRunId"), o.optional("result"),
                        subagentOutcome(d, o.optional("outcome"), path + ".outcome"), envelope(o, false));
            }
            case "SUBAGENT_ERROR" -> {
                Decoder.Obj o = d.object(value, path, with(BASE, "subagentRunId", "message", "code"));
                yield new SubagentError(o.string("subagentRunId"), o.string("message"), o.optString("code"),
                        envelope(o, false));
            }
            case "RAW" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "event", "source"));
                yield new Raw(o.requiredAny("event"), o.optString("source"), envelope(o, true));
            }
            case "CUSTOM" -> {
                Decoder.Obj o = d.object(value, path, with(ATTRIBUTABLE, "name", "value"));
                yield new Custom(o.string("name"), o.requiredAny("value"), envelope(o, true));
            }
            default -> {
                d.warn("dropped event of unrecognised type '" + type + "'");
                yield null;
            }
        };
    }

    private static Envelope envelope(Decoder.Obj o, boolean attributable) {
        Long timestamp = o.optInteger("timestamp", -Decoder.MAX_SAFE, Decoder.MAX_SAFE);
        Object rawEvent = o.optional("rawEvent");
        Map<String, Object> metadata = o.optObject("metadata");
        String subagent = attributable ? o.optString("subagentRunId") : null;
        if (timestamp == null && rawEvent == null && metadata == null && subagent == null) {
            return Envelope.NONE;
        }
        return new Envelope(timestamp, rawEvent, metadata, subagent);
    }

    private static String textRole(Decoder.Obj o) {
        String role = o.optString("role");
        if (role != null && !List.of("developer", "system", "assistant", "user").contains(role)) {
            throw new AgUiFormatException(o.path() + ".role '" + role + "' is not a text message role");
        }
        return role;
    }

    private static RunOutcome runOutcome(Decoder d, Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)) {
            throw new AgUiFormatException(path + " must be an outcome object with a string type");
        }
        switch (type) {
            case "success" -> {
                Decoder.Obj o = d.object(value, path, Set.of("type", "pendingToolCallIds"));
                return new RunOutcome.Success(o.optStrings("pendingToolCallIds"));
            }
            case "interrupt" -> {
                Decoder.Obj o = d.object(value, path, Set.of("type", "interrupts"));
                List<Interrupt> interrupts = o.items("interrupts", (item, p) -> Codec.interrupt(d, item, p));
                if (interrupts.isEmpty()) {
                    throw new AgUiFormatException(path + ".interrupts must not be empty");
                }
                return new RunOutcome.Interrupted(interrupts);
            }
            case "cancelled" -> {
                d.object(value, path, Set.of("type"));
                return new RunOutcome.Cancelled();
            }
            default -> {
                d.warn("stripped " + path + ": unrecognised outcome '" + type + "'");
                return null;
            }
        }
    }

    private static SubagentOutcome subagentOutcome(Decoder d, Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)) {
            throw new AgUiFormatException(path + " must be an outcome object with a string type");
        }
        switch (type) {
            case "success" -> {
                d.object(value, path, Set.of("type"));
                return new SubagentOutcome.Success();
            }
            case "suspended" -> {
                Decoder.Obj o = d.object(value, path, Set.of("type", "interruptIds"));
                return new SubagentOutcome.Suspended(o.optStrings("interruptIds"));
            }
            default -> {
                d.warn("stripped " + path + ": unrecognised outcome '" + type + "'");
                return null;
            }
        }
    }
}
