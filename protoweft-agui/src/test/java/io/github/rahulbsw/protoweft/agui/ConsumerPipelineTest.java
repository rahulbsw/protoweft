package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client's reading pipeline: SSE framing and chunk expansion. */
class ConsumerPipelineTest {

    private static final Envelope N = Envelope.NONE;

    private static List<String> sse(String text, int max) throws IOException {
        SseReader reader = new SseReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), max);
        List<String> out = new ArrayList<>();
        String data;
        while ((data = reader.next()) != null) {
            out.add(data);
        }
        return out;
    }

    @Test
    void sseAcceptsTheFullGrammar() throws IOException {
        assertThat(sse("data: a\n\ndata: b\r\n\r\ndata: c\r\rdata:d\n\n", 100)).containsExactly("a", "b", "c", "d");
        assertThat(sse(": keep-alive\nevent: x\nid: 7\nretry: 5\ndata: one\ndata: two\n\n", 100))
                .containsExactly("one\ntwo");
        assertThat(sse("data: unterminated", 100)).containsExactly("unterminated");
        assertThat(sse("\n\n: only comments\n\n", 100)).isEmpty();
        assertThatThrownBy(() -> sse("data: " + "x".repeat(200) + "\n\n", 100)).isInstanceOf(IOException.class);
        // Many short data lines are limited as one event, not line by line.
        assertThatThrownBy(() -> sse("data: x\n".repeat(60) + "\n", 100)).isInstanceOf(IOException.class)
                .hasMessageContaining("event larger");
        assertThat(sse("data: x\n".repeat(20) + "\n", 100)).singleElement().asString().hasSize(39);
    }

    private static List<AgUiEvent> expand(AgUiEvent... events) {
        ChunkExpander expander = new ChunkExpander();
        List<AgUiEvent> out = new ArrayList<>();
        for (AgUiEvent e : events) {
            out.addAll(expander.expand(e));
        }
        return out;
    }

    @Test
    void textChunksExpandAndCloseWhenTheStreamCannotContinue() {
        Envelope meta = new Envelope(5L, null, Map.of("finish", "stop"), null);
        List<AgUiEvent> out = expand(
                new TextMessageChunk("m1", null, "Hel", null, N),
                new TextMessageChunk(null, null, "lo", null, N),
                new TextMessageChunk(null, null, null, null, meta),     // metadata-only continuation
                new Raw(Map.of(), null, N),                                // RAW stands aside
                new TextMessageChunk("m2", null, "Bye", null, N),         // a new id closes the previous item
                new RunFinished("t", "r", null, null, null, N));         // run-level events close every lane
        assertThat(out).containsExactly(
                new TextMessageStart("m1", null, null, N),
                new TextMessageContent("m1", "Hel", N),
                new TextMessageContent("m1", "lo", N),
                new TextMessageContent("m1", "", new Envelope(null, null, Map.of("finish", "stop"), null)),
                new Raw(Map.of(), null, N),
                new TextMessageEnd("m1", N),
                new TextMessageStart("m2", null, null, N),
                new TextMessageContent("m2", "Bye", N),
                new TextMessageEnd("m2", N),
                new RunFinished("t", "r", null, null, null, N));
    }

    @Test
    void toolAndReasoningChunks() {
        List<AgUiEvent> out = expand(
                new ToolCallChunk("c1", "search", "m1", "{\"q\"", N),
                new ToolCallChunk(null, null, null, ":1}", N),
                new StateSnapshot(null, N),                                // a state event closes the lane
                new ReasoningMessageChunk("r1", "hmm", N),
                new Custom("x.y", 1, N));
        assertThat(TestClient.types(out)).containsExactly("TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_ARGS",
                "TOOL_CALL_END", "STATE_SNAPSHOT", "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT",
                "REASONING_MESSAGE_END", "CUSTOM");
    }

    @Test
    void chunkViolations() {
        assertThatThrownBy(() -> expand(new TextMessageChunk(null, null, "x", null, N)))
                .isInstanceOf(AgUiFormatException.class).hasMessageContaining("must carry its id");
        assertThatThrownBy(() -> expand(new ToolCallChunk("c1", null, null, "{}", N)))
                .isInstanceOf(AgUiFormatException.class).hasMessageContaining("toolCallName");
        assertThatThrownBy(() -> expand(new TextMessageChunk("m", null, "a", null, N),
                new TextMessageChunk(null, "user", "b", null, N)))
                .isInstanceOf(AgUiFormatException.class).hasMessageContaining("conflicting");
        // Two subagents streaming at once: an untagged continuation has no unique referent.
        assertThatThrownBy(() -> expand(new TextMessageChunk("a", null, "x", null, Envelope.of("s1")),
                new TextMessageChunk("b", null, "y", null, Envelope.of("s2")),
                new TextMessageChunk(null, null, "z", null, N)))
                .isInstanceOf(AgUiFormatException.class).hasMessageContaining("ambiguous");
    }

    @Test
    void subagentLanesAreIndependent() {
        List<AgUiEvent> out = expand(
                new TextMessageChunk("a", null, "x", null, Envelope.of("s1")),
                new TextMessageChunk("p", null, "parent", null, N),
                new TextMessageChunk(null, null, " more", null, N),       // continues the parent's stream
                new SubagentFinished("s1", null, null, N));                // ends s1's lane only
        assertThat(TestClient.types(out)).containsExactly("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END",
                "SUBAGENT_FINISHED");
        assertThat(((TextMessageEnd) out.get(5)).messageId()).isEqualTo("a");
    }
}
