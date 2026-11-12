package io.github.rahulbsw.protoweft.agui;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.Custom;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.Envelope;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.StateSnapshot;
import io.github.rahulbsw.protoweft.agui.internal.proto.Events;
import io.github.rahulbsw.protoweft.agui.internal.proto.Patch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The binary binding's framing and the rules the byte corpus does not exercise. */
class ProtoCodecTest {

    private static byte[] frame(AgUiEvent event) {
        return ProtoBinding.frame(EventCodec.encode(event));
    }

    private static AgUiEvent decode(byte[] payload, List<String> warnings) {
        Map<String, Object> json = ProtoBinding.decode(payload, warnings);
        return json == null ? null : EventCodec.decode(new Decoder(warnings), json);
    }

    private static Events.BaseEvent base(Events.EventType type) {
        return Events.BaseEvent.newBuilder().setType(type).build();
    }

    @Test
    void theBindingIsAvailableWithProtobufOnTheClasspath() {
        assertThat(ProtoBinding.available()).isTrue();
    }

    @Test
    void framesSurviveAnyChunkingAndAStreamEndingMidFrameReadsAsTheEnd() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(frame(new Custom("a", 1, Envelope.NONE)));
        body.write(frame(new Custom("b", List.of(true), Envelope.NONE)));
        byte[] whole = body.toByteArray();
        // One byte per read, as a slow network might deliver it.
        ProtoBinding.FrameReader reader = new ProtoBinding.FrameReader(trickle(whole), 1024);
        List<String> warnings = new ArrayList<>();
        assertThat(decode(reader.next(), warnings)).isEqualTo(new Custom("a", 1, Envelope.NONE));
        assertThat(decode(reader.next(), warnings)).isEqualTo(new Custom("b", List.of(true), Envelope.NONE));
        assertThat(reader.next()).isNull();

        int first = frame(new Custom("a", 1, Envelope.NONE)).length;
        for (int cut : new int[] {2, 4, first - 1, first + 2, whole.length - 1}) {
            ProtoBinding.FrameReader truncated = new ProtoBinding.FrameReader(
                    new ByteArrayInputStream(Arrays.copyOf(whole, cut)), 1024);
            int complete = 0;
            while (truncated.next() != null) {
                complete++;
            }
            assertThat(complete).as("whole frames before a cut at %d", cut).isEqualTo(cut < first ? 0 : 1);
        }
    }

    @Test
    void anOversizedFrameFailsBeforeItsBodyIsRead() {
        byte[] header = {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        ProtoBinding.FrameReader reader = new ProtoBinding.FrameReader(new ByteArrayInputStream(header), 1024);
        assertThatThrownBy(reader::next).isInstanceOf(IOException.class).hasMessageContaining("larger than 1024");
    }

    @Test
    void anEventOfAFutureTypeIsDroppedWithAWarning() {
        byte[] future = Events.Event.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder()
                        .addLengthDelimited(ByteString.copyFrom(new byte[] {0x0a, 0x00})).build())
                .build()).build().toByteArray();
        List<String> warnings = new ArrayList<>();
        assertThat(ProtoBinding.decode(future, warnings)).isNull();
        assertThat(warnings).singleElement().asString().contains("does not know");
    }

    @Test
    void malformedFramesAreFatal() {
        byte[] twoEvents = concat(
                Events.Event.newBuilder().setCustom(Events.CustomEvent.newBuilder()
                        .setBaseEvent(base(Events.EventType.CUSTOM)).setName("a")).build().toByteArray(),
                Events.Event.newBuilder().setRaw(Events.RawEvent.newBuilder()
                        .setBaseEvent(base(Events.EventType.RAW))).build().toByteArray());
        byte[] mislabelled = Events.Event.newBuilder().setCustom(Events.CustomEvent.newBuilder()
                .setBaseEvent(base(Events.EventType.RAW)).setName("a")).build().toByteArray();
        byte[] noBase = Events.Event.newBuilder().setCustom(Events.CustomEvent.newBuilder().setName("a"))
                .build().toByteArray();
        for (byte[] bad : List.of(new byte[0], new byte[] {(byte) 0xff, (byte) 0xff}, twoEvents, mislabelled, noBase)) {
            assertThatThrownBy(() -> ProtoBinding.decode(bad, new ArrayList<>()))
                    .as(Arrays.toString(bad)).isInstanceOf(AgUiFormatException.class);
        }
    }

    @Test
    void unrecognisedValuesReachTheSameDecoderAsOnSse() {
        // A patch op number this build has no name for, and an outcome it does not know.
        Events.Event delta = Events.Event.newBuilder().setStateDelta(Events.StateDeltaEvent.newBuilder()
                .setBaseEvent(base(Events.EventType.STATE_DELTA))
                .addDelta(Patch.JsonPatchOperation.newBuilder().setOpValue(42).setPath("/x"))
                .addDelta(Patch.JsonPatchOperation.newBuilder().setOp(Patch.JsonPatchOperationType.REMOVE).setPath("/y")))
                .build();
        assertSameAsSse(delta.toByteArray(),
                "{\"type\":\"STATE_DELTA\",\"delta\":[{\"op\":\"42\",\"path\":\"/x\"},{\"op\":\"remove\",\"path\":\"/y\"}]}");

        Events.Event finished = Events.Event.newBuilder().setRunFinished(Events.RunFinishedEvent.newBuilder()
                .setBaseEvent(base(Events.EventType.RUN_FINISHED)).setThreadId("t").setRunId("r")
                .setOutcome("teleported")).build();
        assertSameAsSse(finished.toByteArray(),
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"t\",\"runId\":\"r\",\"outcome\":{\"type\":\"teleported\"}}");
    }

    /** The frame decodes to the event, and the warnings, that the same event sent as SSE JSON gives. */
    private static void assertSameAsSse(byte[] frame, String sseJson) {
        List<String> sseWarnings = new ArrayList<>();
        AgUiEvent viaSse = EventCodec.decode(new Decoder(sseWarnings), Json.parse(sseJson));
        List<String> protoWarnings = new ArrayList<>();
        assertThat(decode(frame, protoWarnings)).isEqualTo(viaSse);
        assertThat(protoWarnings).isEqualTo(sseWarnings).isNotEmpty();
    }

    @Test
    void openJsonKeepsItsShapeAndNestingIsBoundedLikeJson() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("z", null);
        state.put("a", List.of(1, 2.5, "s", false, Map.of()));
        Object deep = state;
        for (int i = 0; i < 150; i++) {
            deep = List.of(deep);
        }
        StateSnapshot ok = new StateSnapshot(Map.of("deep", deep), Envelope.NONE);
        AgUiEvent back = decode(Arrays.copyOfRange(frame(ok), 4, frame(ok).length), new ArrayList<>());
        assertThat(Json.parse(Json.write(EventCodec.encode(back)))).isEqualTo(Json.parse(Json.write(EventCodec.encode(ok))));

        Object tooDeep = "leaf";
        for (int i = 0; i < Json.MAX_DEPTH + 5; i++) {
            tooDeep = List.of(tooDeep);
        }
        byte[] payload = ProtoCodec.encode(EventCodec.encode(new StateSnapshot(tooDeep, Envelope.NONE)));
        assertThatThrownBy(() -> ProtoBinding.decode(payload, new ArrayList<>()))
                .isInstanceOf(AgUiFormatException.class);
    }

    @Test
    void theWireDoesNotDependOnTheDefaultLocale() {
        // In Turkish, "I".toLowerCase() is a dotless "ı": RUN_FINISHED must still find its envelope arm.
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            AgUiEvent.RunFinished finished = new AgUiEvent.RunFinished("t", "r", null, null, null, Envelope.NONE);
            byte[] f = frame(finished);
            assertThat(decode(Arrays.copyOfRange(f, 4, f.length), new ArrayList<>())).isEqualTo(finished);
            assertThat(AgUiServlet.binding("APPLICATION/VND.AG-UI.EVENT+PROTO", true)).isEqualTo(AgUiProtocol.EVENT_PROTOBUF);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void numbersInsideOpenJsonTravelAsDoubles() {
        // The binary wire is narrower than JSON here, as the spec says: 2^60 + 1 is not a double.
        long big = (1L << 60) + 1;
        byte[] f = frame(new Custom("n", Map.of("big", big, "small", 7), Envelope.NONE));
        Custom back = (Custom) decode(Arrays.copyOfRange(f, 4, f.length), new ArrayList<>());
        Map<?, ?> value = (Map<?, ?>) back.value();
        assertThat(value.get("small")).isEqualTo(7);
        assertThat(((Number) value.get("big")).doubleValue()).isEqualTo((double) big);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static InputStream trickle(byte[] bytes) {
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }
}
