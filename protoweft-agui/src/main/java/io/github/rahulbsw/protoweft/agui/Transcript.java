package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A conversation as a consumer holds it: messages assembled from the event stream, and the shared
 * state. Applies the spec's consumer rules: metadata merges key by key into the item an event
 * builds; tool calls attach to their parent assistant message; encrypted reasoning values are stored
 * with the message or call they name; patches apply atomically and a patch that does not apply is
 * warned about and skipped; {@code MESSAGES_SNAPSHOT} reconciles by id.
 *
 * <p>Not thread-safe.
 */
final class Transcript {

    /** One message being held, mutable while it streams. */
    private static final class Entry {
        String id;
        String role;
        StringBuilder text;
        Object content;
        String name;
        String encryptedValue;
        Map<String, Object> metadata;
        String subagentRunId;
        List<Call> toolCalls;
        String toolCallId;
        String error;
        String activityType;
        Map<String, Object> activity;
    }

    private static final class Call {
        String id;
        String name;
        StringBuilder args = new StringBuilder();
        String encryptedValue;
        Map<String, Object> metadata;
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byId = new HashMap<>();
    private final Map<String, Call> calls = new HashMap<>();
    private final Consumer<String> warnings;
    private Object state;

    Transcript(List<AgUiMessage> messages, Object state, Consumer<String> warnings) {
        this.warnings = warnings;
        this.state = state == null ? new LinkedHashMap<>() : Json.copy(state);
        messages.forEach(m -> add(entry(m)));
    }

    Object state() {
        return Json.copy(state);
    }

    List<AgUiMessage> messages() {
        return entries.stream().map(Transcript::record).toList();
    }

    void append(AgUiMessage message) {
        add(entry(message));
    }

    /** The call's name and argument text, if the transcript holds it. */
    String[] call(String toolCallId) {
        Call c = calls.get(toolCallId);
        return c == null ? null : new String[] {c.name, c.args.toString()};
    }

    void apply(AgUiEvent event) {
        Envelope env = event.envelope() == null ? Envelope.NONE : event.envelope();
        if (event instanceof TextMessageStart e) {
            Entry m = byId.get(e.messageId());
            if (m == null) {
                m = add(textEntry(e.messageId(), e.role() == null ? "assistant" : e.role(), env.subagentRunId()));
            }
            if (m.name == null) {
                m.name = e.name();
            }
            merge(m, env);
        } else if (event instanceof TextMessageContent e) {
            Entry m = byId.get(e.messageId());
            text(m).append(e.delta());
            merge(m, env);
        } else if (event instanceof TextMessageEnd e) {
            merge(byId.get(e.messageId()), env);
        } else if (event instanceof ReasoningMessageStart e) {
            Entry m = byId.get(e.messageId());
            merge(m != null ? m : add(textEntry(e.messageId(), "reasoning", env.subagentRunId())), env);
        } else if (event instanceof ReasoningMessageContent e) {
            Entry m = byId.get(e.messageId());
            text(m).append(e.delta());
            merge(m, env);
        } else if (event instanceof ReasoningMessageEnd e) {
            merge(byId.get(e.messageId()), env);
        } else if (event instanceof ToolCallStart e) {
            Call c = calls.get(e.toolCallId());
            if (c == null) {
                c = new Call();
                c.id = e.toolCallId();
                c.name = e.toolCallName();
                calls.put(c.id, c);
                Entry parent = e.parentMessageId() == null ? null : byId.get(e.parentMessageId());
                if (parent == null || !parent.role.equals("assistant")) {
                    String id = e.parentMessageId() != null && parent == null ? e.parentMessageId()
                            : UUID.randomUUID().toString();
                    parent = add(textEntry(id, "assistant", env.subagentRunId()));
                    parent.text = null;
                }
                if (parent.toolCalls == null) {
                    parent.toolCalls = new ArrayList<>();
                }
                parent.toolCalls.add(c);
            }
            c.metadata = mergeInto(c.metadata, env.metadata());
        } else if (event instanceof ToolCallArgs e) {
            Call c = calls.get(e.toolCallId());
            c.args.append(e.delta());
            c.metadata = mergeInto(c.metadata, env.metadata());
        } else if (event instanceof ToolCallEnd e) {
            Call c = calls.get(e.toolCallId());
            c.metadata = mergeInto(c.metadata, env.metadata());
        } else if (event instanceof ToolCallResult e) {
            Entry m = new Entry();
            m.id = e.messageId();
            m.role = "tool";
            m.content = e.content();
            m.toolCallId = e.toolCallId();
            m.subagentRunId = env.subagentRunId();
            m.metadata = mergeInto(null, env.metadata());
            add(m);
        } else if (event instanceof ReasoningEncryptedValue e) {
            encrypted(e);
        } else if (event instanceof StateSnapshot e) {
            state = Json.copy(e.snapshot());
        } else if (event instanceof StateDelta e) {
            try {
                state = JsonPatch.apply(state, e.delta());
            } catch (JsonPatch.PatchException x) {
                warnings.accept("state delta did not apply and was skipped: " + x.getMessage());
            }
        } else if (event instanceof MessagesSnapshot e) {
            reconcile(e.messages());
        } else if (event instanceof ActivitySnapshot e) {
            activity(e, env);
        } else if (event instanceof ActivityDelta e) {
            activityDelta(e, env);
        }
    }

    private void encrypted(ReasoningEncryptedValue e) {
        if (e.subtype().equals("tool-call") && calls.containsKey(e.entityId())) {
            calls.get(e.entityId()).encryptedValue = e.encryptedValue();
        } else if (e.subtype().equals("message") && byId.containsKey(e.entityId())
                && !byId.get(e.entityId()).role.equals("activity")) {
            byId.get(e.entityId()).encryptedValue = e.encryptedValue();
        } else {
            warnings.accept("encrypted reasoning value for unknown " + e.subtype() + " '" + e.entityId() + "' was dropped");
        }
    }

    private void activity(ActivitySnapshot e, Envelope env) {
        Entry m = byId.get(e.messageId());
        if (m == null) {
            m = new Entry();
            m.id = e.messageId();
            m.role = "activity";
            add(m);
        } else if (!m.role.equals("activity")) {
            warnings.accept("activity snapshot names non-activity message '" + e.messageId() + "'; skipped");
            return;
        } else if (Boolean.FALSE.equals(e.replace())) {
            m.metadata = mergeInto(m.metadata, env.metadata());
            return;
        }
        m.activityType = e.activityType();
        m.activity = copyObject(e.content());
        m.subagentRunId = env.subagentRunId();
        m.metadata = mergeInto(m.metadata, env.metadata());
    }

    private void activityDelta(ActivityDelta e, Envelope env) {
        Entry m = byId.get(e.messageId());
        if (m == null || !m.role.equals("activity")) {
            warnings.accept("activity delta for unknown activity '" + e.messageId() + "' was skipped");
            return;
        }
        try {
            Object patched = JsonPatch.apply(m.activity, e.patch());
            if (!(patched instanceof Map)) {
                warnings.accept("activity delta for '" + e.messageId() + "' would make its content a non-object; skipped");
                return;
            }
            m.activity = copyObject(patched);
            m.activityType = e.activityType();
            m.metadata = mergeInto(m.metadata, env.metadata());
        } catch (JsonPatch.PatchException x) {
            warnings.accept("activity delta for '" + e.messageId() + "' did not apply and was skipped: " + x.getMessage());
        }
    }

    /** Spec "MESSAGES_SNAPSHOT": replace in place, append new, drop absent except client-only roles. */
    private void reconcile(List<AgUiMessage> snapshot) {
        Map<String, AgUiMessage> incoming = new LinkedHashMap<>();
        snapshot.forEach(m -> incoming.put(m.id(), m));
        boolean hasActivity = snapshot.stream().anyMatch(ActivityMessage.class::isInstance);
        boolean hasReasoning = snapshot.stream().anyMatch(ReasoningMessage.class::isInstance);
        List<Entry> kept = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (Entry e : entries) {
            AgUiMessage replacement = incoming.get(e.id);
            if (replacement != null) {
                kept.add(entry(replacement));
                placed.add(e.id);
            } else if ((e.role.equals("activity") && !hasActivity) || (e.role.equals("reasoning") && !hasReasoning)) {
                kept.add(e);
            }
        }
        incoming.forEach((id, m) -> {
            if (!placed.contains(id)) {
                kept.add(entry(m));
            }
        });
        entries.clear();
        byId.clear();
        calls.clear();
        kept.forEach(this::add);
    }

    // ---- entries <-> records ---------------------------------------------------------

    private Entry add(Entry e) {
        entries.add(e);
        byId.put(e.id, e);
        if (e.toolCalls != null) {
            e.toolCalls.forEach(c -> calls.put(c.id, c));
        }
        return e;
    }

    private static Entry textEntry(String id, String role, String subagentRunId) {
        Entry e = new Entry();
        e.id = id;
        e.role = role;
        e.text = new StringBuilder();
        e.subagentRunId = subagentRunId;
        return e;
    }

    private static StringBuilder text(Entry e) {
        if (e.text == null) {
            e.text = new StringBuilder();
        }
        return e.text;
    }

    private static void merge(Entry e, Envelope env) {
        e.metadata = mergeInto(e.metadata, env.metadata());
    }

    /** Key by key, last write wins, no recursion. */
    private static Map<String, Object> mergeInto(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return target;
        }
        Map<String, Object> out = target == null ? new LinkedHashMap<>() : target;
        source.forEach((k, v) -> out.put(k, Json.copy(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyObject(Object value) {
        return (Map<String, Object>) Json.copy(value);
    }

    private static Entry entry(AgUiMessage m) {
        Entry e = new Entry();
        e.id = m.id();
        e.role = m.role();
        e.metadata = m.metadata() == null ? null : copyObject(m.metadata());
        e.subagentRunId = m.subagentRunId();
        if (m instanceof DeveloperMessage x) {
            e.text = new StringBuilder(x.content());
            e.name = x.name();
            e.encryptedValue = x.encryptedValue();
        } else if (m instanceof SystemMessage x) {
            e.text = new StringBuilder(x.content());
            e.name = x.name();
            e.encryptedValue = x.encryptedValue();
        } else if (m instanceof AssistantMessage x) {
            e.text = x.content() == null ? null : new StringBuilder(x.content());
            e.name = x.name();
            e.encryptedValue = x.encryptedValue();
            if (x.toolCalls() != null) {
                e.toolCalls = new ArrayList<>();
                for (ToolCall c : x.toolCalls()) {
                    Call call = new Call();
                    call.id = c.id();
                    call.name = c.name();
                    call.args.append(c.arguments());
                    call.encryptedValue = c.encryptedValue();
                    call.metadata = c.metadata() == null ? null : copyObject(c.metadata());
                    e.toolCalls.add(call);
                }
            }
        } else if (m instanceof UserMessage x) {
            e.content = x.content();
            e.name = x.name();
            e.encryptedValue = x.encryptedValue();
        } else if (m instanceof ToolMessage x) {
            e.content = x.content();
            e.toolCallId = x.toolCallId();
            e.error = x.error();
            e.encryptedValue = x.encryptedValue();
        } else if (m instanceof ActivityMessage x) {
            e.activityType = x.activityType();
            e.activity = copyObject(x.content());
        } else if (m instanceof ReasoningMessage x) {
            e.text = new StringBuilder(x.content());
            e.encryptedValue = x.encryptedValue();
        }
        return e;
    }

    private static AgUiMessage record(Entry e) {
        String text = e.text == null ? null : e.text.toString();
        return switch (e.role) {
            case "developer" -> new DeveloperMessage(e.id, text == null ? "" : text, e.name, e.encryptedValue,
                    e.metadata, e.subagentRunId);
            case "system" -> new SystemMessage(e.id, text == null ? "" : text, e.name, e.encryptedValue, e.metadata,
                    e.subagentRunId);
            case "user" -> new UserMessage(e.id, e.content != null ? e.content : text == null ? "" : text, e.name,
                    e.encryptedValue, e.metadata, e.subagentRunId);
            case "tool" -> new ToolMessage(e.id, e.content, e.toolCallId, e.error, e.encryptedValue, e.metadata,
                    e.subagentRunId);
            case "activity" -> new ActivityMessage(e.id, e.activityType, e.activity, e.metadata, e.subagentRunId);
            case "reasoning" -> new ReasoningMessage(e.id, text == null ? "" : text, e.encryptedValue, e.metadata,
                    e.subagentRunId);
            default -> new AssistantMessage(e.id, text, e.toolCalls == null ? null : e.toolCalls.stream()
                    .map(c -> new ToolCall(c.id, c.name, c.args.toString(), c.encryptedValue, c.metadata)).toList(),
                    e.name, e.encryptedValue, e.metadata, e.subagentRunId);
        };
    }
}
