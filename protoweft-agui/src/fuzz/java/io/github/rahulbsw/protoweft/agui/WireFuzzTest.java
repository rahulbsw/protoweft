package io.github.rahulbsw.protoweft.agui;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fuzzes the parsers that read untrusted AG-UI JSON (raw or generated, see {@link FuzzJson}): the
 * run input a server receives, and events a client receives. Any input is either accepted or
 * rejected with {@link AgUiFormatException} (nothing else may escape), and whatever is accepted must
 * survive an encode–decode round trip unchanged and validate against the official schema.
 *
 * <p>Regression mode (the default) runs the seeds under {@code WireFuzzTestInputs/}; fuzz with
 * {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class WireFuzzTest {

    private static final List<String> WORDS = List.of("type", "id", "role", "content", "messages", "threadId", "runId",
            "messageId", "toolCallId", "delta", "user", "assistant", "tool", "text", "image", "source", "data", "url",
            "file", "value", "mimeType", "subagentRunId", "metadata", "outcome", "success", "interrupt", "cancelled",
            "interrupts", "pendingToolCallIds", "resume", "status", "resolved", "op", "path", "add", "/a", "~",
            "TEXT_MESSAGE_START", "RUN_FINISHED", "STATE_DELTA", "TOOL_CALL_RESULT", "1.0", "", "x");

    private static final List<Object> INPUTS = List.of(Codec.input(Samples.INPUT),
            Codec.input(RunAgentInput.of("t", "r", List.of(AgUiMessage.UserMessage.text("u", "hi")))));

    private static final List<Object> EVENTS = new ArrayList<>(Samples.events().stream()
            .map(e -> (Object) EventCodec.encode(e)).toList());

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void runInput(byte[] input) {
        RunAgentInput parsed;
        try {
            parsed = Codec.input(new Decoder(), Json.parse(FuzzJson.document(input, WORDS, INPUTS)), "input");
        } catch (AgUiFormatException rejected) {
            return;
        }
        Map<String, Object> json = Codec.input(parsed);
        check(Codec.input(new Decoder(), Json.parse(Json.write(json)), "input").equals(parsed), "input round trip");
        List<String> errors = OfficialSchema.errors("RunAgentInput", json);
        check(errors.isEmpty(), "accepted input violates the schema: " + errors);
    }

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void event(byte[] input) {
        AgUiEvent parsed;
        try {
            parsed = EventCodec.decode(new Decoder(), Json.parse(FuzzJson.document(input, WORDS, EVENTS)));
        } catch (AgUiFormatException rejected) {
            return;
        }
        if (parsed == null) {
            return;   // dropped with a warning
        }
        Map<String, Object> json = EventCodec.encode(parsed);
        check(parsed.equals(EventCodec.decode(new Decoder(), Json.parse(Json.write(json)))), "event round trip");
        List<String> errors = OfficialSchema.errors("Event", json);
        check(errors.isEmpty(), "accepted event violates the schema: " + errors);
    }

    /**
     * The client's whole reading path on arbitrary bytes: SSE framing, decoding, chunk expansion and
     * sequence verification. A stream is read to its end or rejected with
     * {@link AgUiFormatException} (or an over-long line); every delivered event re-encodes to JSON
     * the official schema accepts.
     */
    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void stream(byte[] input) {
        java.util.List<String> warnings = new ArrayList<>();
        ConsumerPipeline pipeline = new ConsumerPipeline(warnings);
        SseReader reader = new SseReader(new java.io.ByteArrayInputStream(input), 64 * 1024);
        try {
            String data;
            while ((data = reader.next()) != null) {
                for (AgUiEvent event : pipeline.accept(data)) {
                    List<String> errors = OfficialSchema.errors("Event", EventCodec.encode(event));
                    check(errors.isEmpty(), "delivered event violates the schema: " + errors);
                }
            }
        } catch (AgUiFormatException | java.io.IOException rejected) {
            // malformed: the run fails
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException(what);
        }
    }
}
