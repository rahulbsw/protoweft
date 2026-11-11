package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodecTest {

    @Test
    void everyEventTypeValidatesAgainstTheOfficialSchemaAndRoundTrips() {
        Set<String> types = new HashSet<>();
        for (AgUiEvent event : Samples.events()) {
            Map<String, Object> json = EventCodec.encode(event);
            assertThat(OfficialSchema.errors("Event", json)).as(event.type() + " " + json).isEmpty();
            Decoder d = new Decoder();
            assertThat(EventCodec.decode(d, Json.parse(Json.write(json)))).isEqualTo(event);
            assertThat(d.warnings()).as(event.type()).isEmpty();
            types.add(event.type());
        }
        assertThat(types).hasSize(31);
    }

    @Test
    void runInputValidatesAndRoundTrips() {
        Map<String, Object> json = Codec.input(Samples.INPUT);
        assertThat(OfficialSchema.errors("RunAgentInput", json)).isEmpty();
        Decoder d = new Decoder();
        assertThat(Codec.input(d, Json.parse(Json.write(json)), "input")).isEqualTo(Samples.INPUT);
        assertThat(d.warnings()).isEmpty();
    }

    @Test
    void absentOptionalFieldsAreOmittedNotNull() {
        String json = Json.write(EventCodec.encode(new TextMessageStart("m", null, null, Envelope.NONE)));
        assertThat(json).isEqualTo("{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m\"}");
        // A required payload that is null is still sent.
        assertThat(Json.write(EventCodec.encode(new Custom("x.y", null, Envelope.NONE))))
                .isEqualTo("{\"type\":\"CUSTOM\",\"name\":\"x.y\",\"value\":null}");
    }

    @Test
    void nullInsideOpenObjectsIsData() {
        Map<String, Object> state = Samples.nullableMap();
        String json = Json.write(EventCodec.encode(new StateSnapshot(state, Envelope.NONE)));
        assertThat(json).contains("\"kept\":null");
    }

    @Test
    void unknownEventTypeIsDroppedWithWarning() {
        Decoder d = new Decoder();
        assertThat(EventCodec.decode(d, Map.of("type", "HOLOGRAM_START", "x", 1))).isNull();
        assertThat(d.warnings()).singleElement().asString().contains("HOLOGRAM_START");
    }

    @Test
    void unknownMemberIsStrippedWithWarning() {
        Decoder d = new Decoder();
        AgUiEvent e = EventCodec.decode(d, Map.of("type", "TEXT_MESSAGE_END", "messageId", "m", "future", true));
        assertThat(e).isEqualTo(new TextMessageEnd("m", Envelope.NONE));
        assertThat(d.warnings()).singleElement().asString().contains("TEXT_MESSAGE_END.future");
    }

    @Test
    void malformedKnownValueIsFatal() {
        assertThatThrownBy(() -> EventCodec.decode(new Decoder(), Map.of("type", "TEXT_MESSAGE_END", "messageId", 7)))
                .isInstanceOf(AgUiFormatException.class).hasMessageContaining("messageId");
        assertThatThrownBy(() -> EventCodec.decode(new Decoder(), Map.of("type", "TEXT_MESSAGE_CONTENT", "messageId", "m")))
                .isInstanceOf(AgUiFormatException.class).hasMessageContaining("delta is required");
        assertThatThrownBy(() -> EventCodec.decode(new Decoder(),
                Map.of("type", "TEXT_MESSAGE_START", "messageId", "m", "role", "tool")))
                .isInstanceOf(AgUiFormatException.class);
        assertThatThrownBy(() -> EventCodec.decode(new Decoder(), Map.of("type", 5)))
                .isInstanceOf(AgUiFormatException.class);
    }

    @Test
    void optionalNullIsReadAsAbsentWithWarning() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "TEXT_MESSAGE_START");
        raw.put("messageId", "m");
        raw.put("role", null);
        Decoder d = new Decoder();
        assertThat(EventCodec.decode(d, raw)).isEqualTo(new TextMessageStart("m", null, null, Envelope.NONE));
        assertThat(d.warnings()).singleElement().asString().contains("role was null");
    }

    @Test
    void unrecognisedUnionMembers() {
        // An unknown content part is dropped from its list; an unknown message role from the history.
        Decoder d = new Decoder();
        RunAgentInput input = Codec.input(d, Map.of("threadId", "t", "runId", "r", "messages", List.of(
                Map.of("id", "a", "role", "user", "content", List.of(Map.of("type", "text", "text", "hi"),
                        Map.of("type", "hologram", "src", "x"))),
                Map.of("id", "b", "role", "oracle", "content", "?"))), "input");
        assertThat(input.messages()).hasSize(1);
        assertThat(((AgUiMessage.UserMessage) input.messages().get(0)).content()).asList().hasSize(1);
        assertThat(d.warnings()).hasSize(2);
        // An unknown run outcome is stripped: absent means success.
        Decoder d2 = new Decoder();
        AgUiEvent finished = EventCodec.decode(d2, Map.of("type", "RUN_FINISHED", "threadId", "t", "runId", "r",
                "outcome", Map.of("type", "teleported")));
        assertThat(((RunFinished) finished).outcome()).isNull();
        assertThat(d2.warnings()).hasSize(1);
        // A media source of an unknown kind is malformed: the spec closes that union.
        assertThatThrownBy(() -> Codec.contentPart(new Decoder(),
                Map.of("type", "image", "source", Map.of("type", "ipfs", "value", "x")), "p"))
                .isInstanceOf(AgUiFormatException.class);
    }

    @Test
    void patchOperationsKeepMembersRfc6902DoesNotDefine() {
        Decoder d = new Decoder();
        List<Object> ops = new ArrayList<>(List.of(Map.of("op", "remove", "path", "/a", "value", 1),
                Map.of("op", "frobnicate", "path", "/a")));
        StateDelta delta = (StateDelta) EventCodec.decode(d, Map.of("type", "STATE_DELTA", "delta", ops));
        assertThat(delta.delta()).containsExactly(Map.of("op", "remove", "path", "/a", "value", 1));
        assertThat(d.warnings()).singleElement().asString().contains("frobnicate");
        assertThatThrownBy(() -> EventCodec.decode(new Decoder(), Map.of("type", "STATE_DELTA",
                "delta", List.of(Map.of("op", "add", "path", "no-slash", "value", 1)))))
                .isInstanceOf(AgUiFormatException.class);
    }

    @Test
    void deepNestingIsRejected() {
        String deep = "[".repeat(Json.MAX_DEPTH + 10) + "]".repeat(Json.MAX_DEPTH + 10);
        assertThatThrownBy(() -> Json.parse(deep)).isInstanceOf(AgUiFormatException.class);
    }
}
