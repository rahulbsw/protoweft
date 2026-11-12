package io.github.rahulbsw.protoweft.agui;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.WireFormat;
import io.github.rahulbsw.protoweft.agui.internal.proto.Events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One AG-UI event as the HTTP + Protobuf binding's {@code ag_ui.Event} envelope, to and from the
 * JSON form the SSE path uses. Decoded events therefore enter the same {@link Decoder} as SSE ones,
 * so stripping, warnings and fatal values are the SSE rules (the spec's "same pipeline").
 *
 * <p>The reshaping mirrors the official TypeScript layer ({@code @ag-ui/proto}), whose output is
 * the byte corpus every encoder must reproduce: the base fields nest under {@code baseEvent}, a run
 * or subagent outcome is flattened to a string plus its payload lists, message content travels as
 * a string, as {@code contentParts} or as {@code activityContent} by role, and content parts and
 * sources become oneofs.
 *
 * <p>Loaded only when protobuf-java is present (see {@link ProtoBinding}).
 */
final class ProtoCodec {

    /** Each JSON nesting level is about three message levels (Value, Struct, map entry) on the wire. */
    private static final int RECURSION_LIMIT = 3 * Json.MAX_DEPTH + 16;
    private static final Set<String> PARTS_ROLES = Set.of("user", "tool");
    private static final Set<String> KNOWN_ROLES = Set.of("developer", "system", "assistant", "user", "tool",
            "activity", "reasoning");
    private static final Set<String> MEDIA = Set.of("image", "audio", "video", "document");

    private ProtoCodec() {
    }

    // ---- encoding ---------------------------------------------------------------------------

    /** The envelope bytes for one event (as {@link EventCodec#encode} produces it), without framing. */
    @SuppressWarnings("unchecked")
    static byte[] encode(Map<String, Object> event) {
        Map<String, Object> rest = new LinkedHashMap<>(event);
        String type = (String) rest.remove("type");
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("type", type);
        move(rest, base, "timestamp");
        move(rest, base, "rawEvent");
        move(rest, base, "metadata");
        rest.put("baseEvent", base);
        switch (type) {
            case "MESSAGES_SNAPSHOT" -> rest.put("messages", list(rest.get("messages")).stream()
                    .map(m -> wireMessage((Map<String, Object>) m)).toList());
            case "RUN_FINISHED" -> flattenOutcome(rest, "pendingToolCallIds", "interrupts");
            case "SUBAGENT_FINISHED" -> flattenOutcome(rest, "interruptIds");
            case "TOOL_CALL_RESULT" -> {
                if (rest.get("content") instanceof List<?> parts) {
                    rest.put("contentParts", parts.stream().map(p -> wirePart((Map<String, Object>) p)).toList());
                    rest.remove("content");
                }
            }
            case "RUN_STARTED" -> {
                if (rest.get("input") instanceof Map<?, ?> input) {
                    Map<String, Object> in = new LinkedHashMap<>((Map<String, Object>) input);
                    in.put("messages", list(in.get("messages")).stream()
                            .map(m -> wireMessage((Map<String, Object>) m)).toList());
                    rest.put("input", in);
                }
            }
            default -> {
            }
        }
        Events.Event.Builder envelope = Events.Event.newBuilder();
        FieldDescriptor arm = Events.Event.getDescriptor().findFieldByName(type.toLowerCase(Locale.ROOT));
        if (arm == null) {
            throw new IllegalArgumentException("no wire form for event type " + type);
        }
        envelope.setField(arm, ProtoWire.fill(envelope.newBuilderForField(arm), rest).build());
        return envelope.build().toByteArray();
    }

    /** A run's or subagent's outcome object becomes the wire's outcome string plus its lists. */
    @SuppressWarnings("unchecked")
    private static void flattenOutcome(Map<String, Object> rest, String... lists) {
        Object outcome = rest.remove("outcome");
        if (outcome instanceof Map<?, ?> o) {
            Map<String, Object> m = (Map<String, Object>) o;
            rest.put("outcome", m.get("type"));
            for (String key : lists) {
                if (m.get(key) != null) {
                    rest.put(key, m.get(key));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wireMessage(Map<String, Object> message) {
        Map<String, Object> wire = new LinkedHashMap<>(message);
        Object content = wire.get("content");
        if (content instanceof List<?> parts) {
            wire.put("contentParts", parts.stream().map(p -> wirePart((Map<String, Object>) p)).toList());
            wire.remove("content");
        } else if ("activity".equals(wire.get("role"))) {
            wire.put("activityContent", content instanceof Map<?, ?> ? content : Map.of());
            wire.remove("content");
        }
        return wire;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wirePart(Map<String, Object> part) {
        String type = String.valueOf(part.get("type"));
        Map<String, Object> body = new LinkedHashMap<>();
        if (type.equals("text")) {
            body.put("text", part.get("text"));
        } else if (MEDIA.contains(type) && part.get("source") instanceof Map<?, ?> s) {
            Map<String, Object> source = new LinkedHashMap<>((Map<String, Object>) s);
            String kind = String.valueOf(source.remove("type"));
            body.put("source", Map.of(kind, source));
        } else {
            throw new IllegalArgumentException("no wire form for content part type " + type);
        }
        copy(part, body, "id");
        copy(part, body, "metadata");
        return Map.of(type, body);
    }

    // ---- decoding ---------------------------------------------------------------------------

    /**
     * One event in its JSON form, or {@code null} when the frame names only events this build
     * does not know (dropped, warned, as an unknown type is on SSE).
     *
     * @throws AgUiFormatException if the bytes are not a well-formed envelope of one event
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> decode(byte[] frame, List<String> warnings) {
        Events.Event envelope;
        try {
            singleArm(frame);
            CodedInputStream in = CodedInputStream.newInstance(frame);
            in.setRecursionLimit(RECURSION_LIMIT);
            envelope = Events.Event.parseFrom(in);
            in.checkLastTagWas(0);
        } catch (InvalidProtocolBufferException e) {
            throw new AgUiFormatException("a frame is not a valid protobuf event: " + e.getMessage());
        } catch (IOException e) {
            throw new AgUiFormatException("a frame is not a valid protobuf event: " + e.getMessage());
        }
        if (envelope.getEventCase() == Events.Event.EventCase.EVENT_NOT_SET) {
            if (envelope.getUnknownFields().asMap().values().stream()
                    .anyMatch(field -> !field.getLengthDelimitedList().isEmpty())) {
                warnings.add("dropped a protobuf event of a type this version does not know");
                return null;
            }
            throw new AgUiFormatException("a protobuf frame carries no event");
        }
        FieldDescriptor arm = Events.Event.getDescriptor().findFieldByNumber(envelope.getEventCase().getNumber());
        String type = arm.getName().toUpperCase(Locale.ROOT);
        Map<String, Object> event = ProtoWire.json((Message) envelope.getField(arm), 0);
        if (!(event.remove("baseEvent") instanceof Map<?, ?> b)) {
            throw new AgUiFormatException("the protobuf " + type + " event has no baseEvent");
        }
        Map<String, Object> base = (Map<String, Object>) b;
        if (!type.equals(base.get("type"))) {
            throw new AgUiFormatException("the envelope carries " + type + " but its base event declares "
                    + base.get("type"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        move(base, out, "timestamp");
        move(base, out, "rawEvent");
        move(base, out, "metadata");
        out.putAll(event);
        switch (type) {
            case "MESSAGES_SNAPSHOT" -> out.put("messages", list(out.get("messages")).stream()
                    .map(m -> jsonMessage((Map<String, Object>) m, warnings)).toList());
            case "RUN_FINISHED" -> {
                nestOutcome(out, "pendingToolCallIds", "interrupts");
                dropEmpty(out, "usage");
            }
            case "RUN_ERROR" -> dropEmpty(out, "usage");
            case "SUBAGENT_FINISHED" -> nestOutcome(out, "interruptIds");
            case "TOOL_CALL_RESULT" -> {
                List<?> parts = list(out.remove("contentParts"));
                if (out.containsKey("content") && !parts.isEmpty()) {
                    throw new AgUiFormatException("TOOL_CALL_RESULT carries both string content and content parts");
                }
                if (!out.containsKey("content")) {
                    out.put("content", jsonParts(parts, warnings));
                }
            }
            case "RUN_STARTED" -> {
                if (out.get("input") instanceof Map<?, ?> input) {
                    Map<String, Object> in = (Map<String, Object>) input;
                    in.put("messages", list(in.get("messages")).stream()
                            .map(m -> jsonMessage((Map<String, Object>) m, warnings)).toList());
                }
            }
            default -> {
            }
        }
        return out;
    }

    /**
     * Rejects an envelope that names two different events: protobuf would keep the last, another
     * runtime might keep another, and the official layer rejects it.
     */
    private static void singleArm(byte[] frame) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(frame);
        Set<Integer> seen = new HashSet<>();
        int tag;
        while ((tag = in.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (Events.Event.getDescriptor().findFieldByNumber(field) != null && seen.add(field) && seen.size() > 1) {
                throw new AgUiFormatException("a protobuf frame carries more than one event");
            }
            in.skipField(tag);
        }
    }

    /** The wire's outcome string plus lists, back to the outcome object (an unknown one rides on for the decoder). */
    private static void nestOutcome(Map<String, Object> out, String... lists) {
        Object wire = out.remove("outcome");
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : lists) {
            List<?> l = list(out.remove(key));
            if (!l.isEmpty()) {
                payload.put(key, l);
            }
        }
        if (!(wire instanceof String type) || type.isEmpty()) {
            out.putAll(payload);   // no outcome: whatever arrived rides on, for the decoder to strip
            return;
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type", type);
        if (type.equals("interrupt")) {
            outcome.put("interrupts", payload.getOrDefault("interrupts", List.of()));
        }
        payload.forEach(outcome::putIfAbsent);
        out.put("outcome", outcome);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMessage(Map<String, Object> wire, List<String> warnings) {
        Map<String, Object> m = new LinkedHashMap<>(wire);
        String role = String.valueOf(m.get("role"));
        List<?> parts = list(m.remove("contentParts"));
        Object activity = m.remove("activityContent");
        boolean known = KNOWN_ROLES.contains(role);
        boolean usesParts = known ? PARTS_ROLES.contains(role) : !parts.isEmpty();
        boolean usesMap = known ? role.equals("activity") : !usesParts && activity != null;
        boolean content = m.containsKey("content");
        if (!known && (content ? 1 : 0) + (parts.isEmpty() ? 0 : 1) + (activity != null ? 1 : 0) > 1) {
            throw new AgUiFormatException("a protobuf message carries more than one content form");
        }
        if (known && (!usesParts && !parts.isEmpty() || activity != null && !usesMap || usesMap && content
                || usesParts && content && !parts.isEmpty())) {
            throw new AgUiFormatException("a protobuf message carries content its role (" + role + ") has no use for");
        }
        if (usesParts && !m.containsKey("content")) {
            m.put("content", jsonParts(parts, warnings));
        }
        if (usesMap && activity != null) {
            m.put("content", activity);
        }
        dropEmpty(m, "toolCalls");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> jsonParts(List<?> parts, List<String> warnings) {
        List<Object> out = new ArrayList<>();
        for (Object p : parts) {
            Map<String, Object> wire = (Map<String, Object>) p;
            if (wire.size() != 1) {
                if (wire.size() > 1) {
                    throw new AgUiFormatException("a protobuf content part carries more than one kind");
                }
                warnings.add("dropped a protobuf content part of a kind this version does not know");
                continue;
            }
            Map.Entry<String, Object> arm = wire.entrySet().iterator().next();
            Map<String, Object> body = (Map<String, Object>) arm.getValue();
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", arm.getKey());
            move(body, part, "id");
            if (arm.getKey().equals("text")) {
                part.put("text", body.get("text"));
            } else if (body.get("source") instanceof Map<?, ?> s) {
                Map<String, Object> wireSource = (Map<String, Object>) s;
                if (wireSource.size() != 1) {
                    throw new AgUiFormatException("a protobuf content source must carry exactly one kind");
                }
                Map.Entry<String, Object> kind = wireSource.entrySet().iterator().next();
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("type", kind.getKey());
                source.putAll((Map<String, Object>) kind.getValue());
                part.put("source", source);
            }
            move(body, part, "metadata");
            out.add(part);
        }
        return out;
    }

    private static void move(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.remove(key));
        }
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private static void dropEmpty(Map<String, Object> m, String key) {
        if (m.get(key) instanceof List<?> l && l.isEmpty()) {
            m.remove(key);
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }
}
