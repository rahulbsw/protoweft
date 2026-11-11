package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Expands the chunked spelling ({@code TEXT_MESSAGE_CHUNK}, {@code TOOL_CALL_CHUNK},
 * {@code REASONING_MESSAGE_CHUNK}) into start / content / end events (spec "Streaming Messages",
 * "The chunked form"). Each owner (the parent agent or a subagent invocation) has one lane holding
 * at most one open chunk stream; the end event is synthesized when the stream can no longer continue.
 * Synthesized events carry the chunk's metadata, raw event and attribution, but not its timestamp.
 *
 * <p>Not thread-safe; one instance per stream.
 */
final class ChunkExpander {

    private enum Kind { TEXT, TOOL, REASONING }

    /** One open chunk stream. */
    private static final class Lane {
        final String owner;
        final Kind kind;
        final String id;
        final String role;
        final String name;
        final String parentMessageId;

        Lane(String owner, Kind kind, String id, String role, String name, String parentMessageId) {
            this.owner = owner;
            this.kind = kind;
            this.id = id;
            this.role = role;
            this.name = name;
            this.parentMessageId = parentMessageId;
        }
    }

    /** Open lanes by owner ({@code ""} for the parent agent). */
    private final Map<String, Lane> lanes = new LinkedHashMap<>();

    /** The events {@code event} stands for, after closing any chunk stream it ends. */
    List<AgUiEvent> expand(AgUiEvent event) {
        List<AgUiEvent> out = new ArrayList<>();
        if (event instanceof TextMessageChunk c) {
            chunk(out, Kind.TEXT, c.messageId(), c.role(), c.name(), null, c.delta(), c.envelope());
        } else if (event instanceof ToolCallChunk c) {
            chunk(out, Kind.TOOL, c.toolCallId(), null, c.toolCallName(), c.parentMessageId(), c.delta(), c.envelope());
        } else if (event instanceof ReasoningMessageChunk c) {
            chunk(out, Kind.REASONING, c.messageId(), null, null, null, c.delta(), c.envelope());
        } else {
            String type = event.type();
            if (type.startsWith("RUN_") || type.equals("MESSAGES_SNAPSHOT")) {
                new ArrayList<>(lanes.keySet()).forEach(owner -> close(out, owner));
            } else if (event instanceof SubagentFinished f) {
                close(out, f.subagentRunId());
            } else if (event instanceof SubagentError f) {
                close(out, f.subagentRunId());
            } else if (!(event instanceof Raw || event instanceof ActivitySnapshot || event instanceof ActivityDelta
                    || event instanceof ReasoningEncryptedValue || event instanceof SubagentStarted)) {
                close(out, owner(event.envelope()));
            }
            out.add(event);
        }
        return out;
    }

    private void chunk(List<AgUiEvent> out, Kind kind, String id, String role, String name, String parent,
                       String delta, Envelope chunkEnv) {
        String tag = chunkEnv == null ? null : chunkEnv.subagentRunId();
        Envelope env = chunkEnv == null ? Envelope.NONE
                : new Envelope(null, chunkEnv.rawEvent(), chunkEnv.metadata(), chunkEnv.subagentRunId());
        Lane lane = id != null ? find(kind, id) : resolve(kind, tag);
        if (lane != null && (id == null || id.equals(lane.id))) {
            if (tag != null && !tag.equals(lane.owner)) {
                fail("a chunk for " + lane.id + " is attributed to " + tag + " but the stream belongs to "
                        + (lane.owner.isEmpty() ? "the parent agent" : lane.owner));
            }
            checkRepeat(kind == Kind.TEXT ? "role" : "toolCallName", kind == Kind.TEXT ? role : name,
                    kind == Kind.TEXT ? lane.role : lane.name, lane, kind == Kind.TEXT ? "assistant" : null);
            checkRepeat(kind == Kind.TEXT ? "name" : "parentMessageId", kind == Kind.TEXT ? name : parent,
                    kind == Kind.TEXT ? lane.name : lane.parentMessageId, lane, null);
            content(out, kind, lane.id, delta, env, true);
            return;
        }
        if (id == null) {
            fail("the first " + kind.name().toLowerCase() + " chunk of an item must carry its id");
        }
        if (kind == Kind.TOOL && name == null) {
            fail("the first TOOL_CALL_CHUNK of a call must carry toolCallName");
        }
        String owner = tag == null ? "" : tag;
        close(out, owner);
        lanes.put(owner, new Lane(owner, kind, id, role == null ? "assistant" : role, name, parent));
        switch (kind) {
            case TEXT -> out.add(new TextMessageStart(id, role, name, env));
            case TOOL -> out.add(new ToolCallStart(id, name, parent, env));
            case REASONING -> out.add(new ReasoningMessageStart(id, env));
        }
        content(out, kind, id, delta, env, false);
    }

    private static void content(List<AgUiEvent> out, Kind kind, String id, String delta, Envelope env,
                                boolean continuation) {
        if (delta == null) {
            // A continuation carrying only metadata still reaches the item it continues.
            if (!continuation || env.metadata() == null) {
                return;
            }
            delta = "";
        }
        switch (kind) {
            case TEXT -> out.add(new TextMessageContent(id, delta, env));
            case TOOL -> out.add(new ToolCallArgs(id, delta, env));
            case REASONING -> out.add(new ReasoningMessageContent(id, delta, env));
        }
    }

    private void checkRepeat(String field, String value, String established, Lane lane, String fallback) {
        if (value != null && !value.equals(established == null ? fallback : established)) {
            fail("a continuation chunk for " + lane.id + " repeats " + field + " with a conflicting value");
        }
    }

    /** The open stream of {@code kind} with this id, in any lane. */
    private Lane find(Kind kind, String id) {
        for (Lane lane : lanes.values()) {
            if (lane.kind == kind && lane.id.equals(id)) {
                return lane;
            }
        }
        return null;
    }

    /**
     * The stream an id-less continuation continues: the tagged owner's, else the parent's, else the
     * sole open stream of its kind; several candidates are ambiguous.
     */
    private Lane resolve(Kind kind, String tag) {
        if (tag != null) {
            Lane lane = lanes.get(tag);
            return lane != null && lane.kind == kind ? lane : null;
        }
        Lane parent = lanes.get("");
        if (parent != null && parent.kind == kind) {
            return parent;
        }
        Lane only = null;
        for (Lane lane : lanes.values()) {
            if (lane.kind == kind) {
                if (only != null) {
                    fail("an untagged continuation chunk is ambiguous between subagents " + only.owner + " and "
                            + lane.owner);
                }
                only = lane;
            }
        }
        return only;
    }

    private void close(List<AgUiEvent> out, String owner) {
        Lane lane = lanes.remove(owner);
        if (lane == null) {
            return;
        }
        Envelope env = Envelope.of(owner.isEmpty() ? null : owner);
        switch (lane.kind) {
            case TEXT -> out.add(new TextMessageEnd(lane.id, env));
            case TOOL -> out.add(new ToolCallEnd(lane.id, env));
            case REASONING -> out.add(new ReasoningMessageEnd(lane.id, env));
        }
    }

    private static String owner(Envelope env) {
        return env == null || env.subagentRunId() == null ? "" : env.subagentRunId();
    }

    private static void fail(String message) {
        throw new AgUiFormatException(Objects.requireNonNull(message));
    }
}
