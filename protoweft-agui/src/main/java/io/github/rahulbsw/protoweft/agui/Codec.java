package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.TokenUsage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ActivityMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.AssistantMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ContentPart;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.DeveloperMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ReasoningMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.SystemMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ToolCall;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ToolMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.agui.RunAgentInput.Context;
import io.github.rahulbsw.protoweft.agui.RunAgentInput.ResumeEntry;
import io.github.rahulbsw.protoweft.agui.RunAgentInput.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON mapping of the protocol's non-event types (messages, content parts, run input, interrupts,
 * usage), following the official schema. Encoding omits optional fields with no value; decoding
 * follows {@link Decoder}'s processing model.
 */
final class Codec {

    private static final Set<String> MESSAGE_BASE = Set.of("id", "role", "name", "encryptedValue", "metadata",
            "subagentRunId");
    private static final Set<String> INPUT = Set.of("threadId", "runId", "protocolVersion", "parentRunId", "state",
            "messages", "tools", "context", "forwardedProps", "resume");

    private Codec() {
    }

    // ---- run input ----------------------------------------------------------------------

    static Map<String, Object> input(RunAgentInput in) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", in.threadId());
        out.put("runId", in.runId());
        put(out, "protocolVersion", in.protocolVersion());
        put(out, "parentRunId", in.parentRunId());
        put(out, "state", in.state());
        out.put("messages", in.messages().stream().map(Codec::message).toList());
        if (!in.tools().isEmpty()) {
            out.put("tools", in.tools().stream().map(Codec::tool).toList());
        }
        if (!in.context().isEmpty()) {
            out.put("context", in.context().stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("description", c.description());
                m.put("value", c.value());
                return m;
            }).toList());
        }
        put(out, "forwardedProps", in.forwardedProps());
        if (!in.resume().isEmpty()) {
            out.put("resume", in.resume().stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("interruptId", r.interruptId());
                m.put("status", r.status());
                put(m, "payload", r.payload());
                put(m, "metadata", r.metadata());
                return m;
            }).toList());
        }
        return out;
    }

    static RunAgentInput input(Decoder d, Object value, String path) {
        Decoder.Obj o = d.object(value, path, INPUT);
        return new RunAgentInput(o.string("threadId"), o.string("runId"), o.optString("protocolVersion"),
                o.optString("parentRunId"), o.optional("state"),
                o.items("messages", (item, p) -> message(d, item, p)),
                o.optItems("tools", (item, p) -> {
                    Decoder.Obj t = d.object(item, p, Set.of("name", "description", "parameters", "metadata"));
                    Object parameters = t.optional("parameters");
                    if (parameters != null && !(parameters instanceof Map) && !(parameters instanceof Boolean)) {
                        throw new AgUiFormatException(p + ".parameters must be a JSON Schema");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> schema = parameters instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                    return new Tool(t.string("name"), t.string("description"), schema, t.optObject("metadata"));
                }),
                o.optItems("context", (item, p) -> {
                    Decoder.Obj c = d.object(item, p, Set.of("description", "value"));
                    return new Context(c.string("description"), c.string("value"));
                }),
                o.optional("forwardedProps"),
                o.optItems("resume", (item, p) -> {
                    Decoder.Obj r = d.object(item, p, Set.of("interruptId", "status", "payload", "metadata"));
                    String status = r.string("status");
                    if (!ResumeEntry.RESOLVED.equals(status) && !ResumeEntry.CANCELLED.equals(status)) {
                        throw new AgUiFormatException(p + ".status must be resolved or cancelled");
                    }
                    return new ResumeEntry(r.string("interruptId"), status, r.optional("payload"),
                            r.optObject("metadata"));
                }));
    }

    static Map<String, Object> tool(Tool t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("description", t.description());
        put(m, "parameters", t.parameters());
        put(m, "metadata", t.metadata());
        return m;
    }

    // ---- messages -----------------------------------------------------------------------

    static Map<String, Object> message(AgUiMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id());
        m.put("role", msg.role());
        if (msg instanceof DeveloperMessage x) {
            m.put("content", x.content());
            put(m, "name", x.name());
            put(m, "encryptedValue", x.encryptedValue());
        } else if (msg instanceof SystemMessage x) {
            m.put("content", x.content());
            put(m, "name", x.name());
            put(m, "encryptedValue", x.encryptedValue());
        } else if (msg instanceof AssistantMessage x) {
            put(m, "content", x.content());
            if (x.toolCalls() != null) {
                m.put("toolCalls", x.toolCalls().stream().map(Codec::toolCall).toList());
            }
            put(m, "name", x.name());
            put(m, "encryptedValue", x.encryptedValue());
        } else if (msg instanceof UserMessage x) {
            m.put("content", content(x.content()));
            put(m, "name", x.name());
            put(m, "encryptedValue", x.encryptedValue());
        } else if (msg instanceof ToolMessage x) {
            m.put("content", content(x.content()));
            m.put("toolCallId", x.toolCallId());
            put(m, "error", x.error());
            put(m, "encryptedValue", x.encryptedValue());
        } else if (msg instanceof ActivityMessage x) {
            m.put("activityType", x.activityType());
            m.put("content", x.content());
        } else if (msg instanceof ReasoningMessage x) {
            m.put("content", x.content());
            put(m, "encryptedValue", x.encryptedValue());
        }
        put(m, "metadata", msg.metadata());
        put(m, "subagentRunId", msg.subagentRunId());
        return m;
    }

    /** A message, or {@code null} (dropped, warned) when its role is not one this version knows. */
    static AgUiMessage message(Decoder d, Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new AgUiFormatException(path + " must be an object");
        }
        if (!(raw.get("role") instanceof String role)) {
            throw new AgUiFormatException(path + ".role must be a string");
        }
        Decoder.Obj o;
        switch (role) {
            case "developer", "system" -> {
                o = d.object(value, path, with(MESSAGE_BASE, "content"));
                String content = o.string("content");
                return role.equals("system")
                        ? new SystemMessage(o.string("id"), content, o.optString("name"), o.optString("encryptedValue"),
                                o.optObject("metadata"), o.optString("subagentRunId"))
                        : new DeveloperMessage(o.string("id"), content, o.optString("name"),
                                o.optString("encryptedValue"), o.optObject("metadata"), o.optString("subagentRunId"));
            }
            case "assistant" -> {
                o = d.object(value, path, with(MESSAGE_BASE, "content", "toolCalls"));
                return new AssistantMessage(o.string("id"), o.optString("content"),
                        o.optItems("toolCalls", (item, p) -> toolCall(d, item, p)), o.optString("name"),
                        o.optString("encryptedValue"), o.optObject("metadata"), o.optString("subagentRunId"));
            }
            case "user" -> {
                o = d.object(value, path, with(MESSAGE_BASE, "content"));
                return new UserMessage(o.string("id"), o.stringOrParts("content"), o.optString("name"),
                        o.optString("encryptedValue"), o.optObject("metadata"), o.optString("subagentRunId"));
            }
            case "tool" -> {
                o = d.object(value, path, Set.of("id", "role", "content", "toolCallId", "error", "encryptedValue",
                        "metadata", "subagentRunId"));
                return new ToolMessage(o.string("id"), o.stringOrParts("content"), o.string("toolCallId"),
                        o.optString("error"), o.optString("encryptedValue"), o.optObject("metadata"),
                        o.optString("subagentRunId"));
            }
            case "activity" -> {
                o = d.object(value, path, Set.of("id", "role", "activityType", "content", "metadata", "subagentRunId"));
                return new ActivityMessage(o.string("id"), o.string("activityType"), o.object("content"),
                        o.optObject("metadata"), o.optString("subagentRunId"));
            }
            case "reasoning" -> {
                o = d.object(value, path, Set.of("id", "role", "content", "encryptedValue", "metadata", "subagentRunId"));
                return new ReasoningMessage(o.string("id"), o.string("content"), o.optString("encryptedValue"),
                        o.optObject("metadata"), o.optString("subagentRunId"));
            }
            default -> {
                d.warn("dropped " + path + ": unrecognised message role '" + role + "'");
                return null;
            }
        }
    }

    static Map<String, Object> toolCall(ToolCall c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", c.name());
        fn.put("arguments", c.arguments());
        m.put("function", fn);
        put(m, "encryptedValue", c.encryptedValue());
        put(m, "metadata", c.metadata());
        return m;
    }

    static ToolCall toolCall(Decoder d, Object value, String path) {
        Decoder.Obj o = d.object(value, path, Set.of("id", "type", "function", "encryptedValue", "metadata"));
        if (!"function".equals(o.string("type"))) {
            throw new AgUiFormatException(path + ".type must be \"function\"");
        }
        Decoder.Obj fn = d.object(o.requiredAny("function"), path + ".function", Set.of("name", "arguments"));
        return new ToolCall(o.string("id"), fn.string("name"), fn.string("arguments"), o.optString("encryptedValue"),
                o.optObject("metadata"));
    }

    // ---- content parts ------------------------------------------------------------------

    /** A string as-is, or a list of content parts as JSON. */
    static Object content(Object content) {
        if (content instanceof List<?> parts) {
            return parts.stream().map(p -> contentPart((ContentPart) p)).toList();
        }
        return content;
    }

    static Map<String, Object> contentPart(ContentPart part) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (part instanceof ContentPart.Text t) {
            m.put("type", "text");
            m.put("text", t.text());
        } else if (part instanceof ContentPart.Media media) {
            m.put("type", media.kind());
            Map<String, Object> s = new LinkedHashMap<>();
            if (media.source() instanceof ContentPart.Source.Data x) {
                s.put("type", "data");
                s.put("value", x.value());
                s.put("mimeType", x.mimeType());
            } else if (media.source() instanceof ContentPart.Source.Url x) {
                s.put("type", "url");
                s.put("value", x.value());
                put(s, "mimeType", x.mimeType());
            } else if (media.source() instanceof ContentPart.Source.File x) {
                s.put("type", "file");
                s.put("value", x.value());
                put(s, "provider", x.provider());
                put(s, "mimeType", x.mimeType());
            }
            m.put("source", s);
        }
        put(m, "id", part.id());
        put(m, "metadata", part.metadata());
        return m;
    }

    /**
     * A content part, or {@code null} (dropped, warned) when its type is not one this version knows.
     * A source of an unknown type is malformed: the spec closes that union.
     */
    static ContentPart contentPart(Decoder d, Object value, String path) {
        if (!(value instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)) {
            throw new AgUiFormatException(path + " must be a content part with a string type");
        }
        if (type.equals("text")) {
            Decoder.Obj o = d.object(value, path, Set.of("type", "id", "text", "metadata"));
            return new ContentPart.Text(o.string("text"), o.optString("id"), o.optional("metadata"));
        }
        if (!ContentPart.Media.KINDS.contains(type)) {
            d.warn("dropped " + path + ": unrecognised content part type '" + type + "'");
            return null;
        }
        Decoder.Obj o = d.object(value, path, Set.of("type", "id", "source", "metadata"));
        Object sourceValue = o.requiredAny("source");
        String sp = path + ".source";
        if (!(sourceValue instanceof Map<?, ?> sm) || !(sm.get("type") instanceof String sourceType)) {
            throw new AgUiFormatException(sp + " must be a source object with a string type");
        }
        ContentPart.Source source = switch (sourceType) {
            case "data" -> {
                Decoder.Obj s = d.object(sourceValue, sp, Set.of("type", "value", "mimeType"));
                yield new ContentPart.Source.Data(s.string("value"), s.string("mimeType"));
            }
            case "url" -> {
                Decoder.Obj s = d.object(sourceValue, sp, Set.of("type", "value", "mimeType"));
                yield new ContentPart.Source.Url(s.string("value"), s.optString("mimeType"));
            }
            case "file" -> {
                Decoder.Obj s = d.object(sourceValue, sp, Set.of("type", "value", "provider", "mimeType"));
                yield new ContentPart.Source.File(s.string("value"), s.optString("provider"), s.optString("mimeType"));
            }
            default -> throw new AgUiFormatException(sp + ".type '" + sourceType + "' is not data, url or file");
        };
        return new ContentPart.Media(type, source, o.optString("id"), o.optional("metadata"));
    }

    // ---- interrupts and usage -----------------------------------------------------------

    static Map<String, Object> interrupt(Interrupt i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("reason", i.reason());
        put(m, "message", i.message());
        put(m, "toolCallId", i.toolCallId());
        put(m, "responseSchema", i.responseSchema());
        put(m, "expiresAt", i.expiresAt());
        put(m, "metadata", i.metadata());
        put(m, "subagentRunId", i.subagentRunId());
        return m;
    }

    static Interrupt interrupt(Decoder d, Object value, String path) {
        Decoder.Obj o = d.object(value, path, Set.of("id", "reason", "message", "toolCallId", "responseSchema",
                "expiresAt", "metadata", "subagentRunId"));
        return new Interrupt(o.string("id"), o.string("reason"), o.optString("message"), o.optString("toolCallId"),
                o.optObject("responseSchema"), o.optString("expiresAt"), o.optObject("metadata"),
                o.optString("subagentRunId"));
    }

    static Map<String, Object> usage(TokenUsage u) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "provider", u.provider());
        put(m, "model", u.model());
        put(m, "inputTokens", u.inputTokens());
        put(m, "outputTokens", u.outputTokens());
        put(m, "totalTokens", u.totalTokens());
        put(m, "reasoningTokens", u.reasoningTokens());
        put(m, "cachedInputTokens", u.cachedInputTokens());
        put(m, "cacheWriteInputTokens", u.cacheWriteInputTokens());
        return m;
    }

    static TokenUsage usage(Decoder d, Object value, String path) {
        Decoder.Obj o = d.object(value, path, Set.of("provider", "model", "inputTokens", "outputTokens",
                "totalTokens", "reasoningTokens", "cachedInputTokens", "cacheWriteInputTokens"));
        long max = Decoder.MAX_SAFE;
        return new TokenUsage(o.optString("provider"), o.optString("model"), o.optInteger("inputTokens", 0, max),
                o.optInteger("outputTokens", 0, max), o.optInteger("totalTokens", 0, max),
                o.optInteger("reasoningTokens", 0, max), o.optInteger("cachedInputTokens", 0, max),
                o.optInteger("cacheWriteInputTokens", 0, max));
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Puts an optional field only when it has a value ("absent means absent"). */
    static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    static Set<String> with(Set<String> base, String... more) {
        java.util.Set<String> out = new java.util.HashSet<>(base);
        out.addAll(List.of(more));
        return Set.copyOf(out);
    }
}
