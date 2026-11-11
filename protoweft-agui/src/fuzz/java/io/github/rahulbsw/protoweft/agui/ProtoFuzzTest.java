package io.github.rahulbsw.protoweft.agui;

import com.code_intelligence.jazzer.junit.FuzzTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fuzzes the HTTP + Protobuf binding's frame decoder on untrusted bytes. Any frame is either
 * accepted or rejected with {@link AgUiFormatException} (nothing else may escape); whatever is
 * accepted re-encodes to a frame that decodes to the same event, and to JSON the official schema
 * accepts.
 *
 * <p>Regression mode (the default) runs the seeds under {@code ProtoFuzzTestInputs/}: the official
 * byte corpus. Fuzz with {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class ProtoFuzzTest {

    @FuzzTest(maxDuration = "2m")
    void frame(byte[] input) {
        AgUiEvent parsed;
        try {
            Map<String, Object> json = ProtoBinding.decode(input, new ArrayList<>());
            if (json == null) {
                return;   // an event type this version does not know, dropped with a warning
            }
            parsed = EventCodec.decode(new Decoder(), json);
        } catch (AgUiFormatException rejected) {
            return;
        }
        if (parsed == null) {
            return;
        }
        Map<String, Object> json = EventCodec.encode(parsed);
        AgUiEvent again = EventCodec.decode(new Decoder(), ProtoBinding.decode(ProtoCodec.encode(json), new ArrayList<>()));
        check(parsed.equals(again), "frame round trip: " + parsed + " became " + again);
        List<String> errors = OfficialSchema.errors("Event", json);
        check(errors.isEmpty(), "accepted event violates the schema: " + errors);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }
}
