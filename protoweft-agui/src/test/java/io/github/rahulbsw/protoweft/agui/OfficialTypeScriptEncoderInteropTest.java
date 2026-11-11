package io.github.rahulbsw.protoweft.agui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protoweft's client and conformance checker reading frames written by the official TypeScript
 * encoder ({@code @ag-ui/encoder} with {@code @ag-ui/proto}), served by
 * {@code interop/agui/ts/encoder-agent.mjs}. Run by {@code scripts/interop/agui.sh}, which sets
 * {@code AGUI_INTEROP_TS_ENCODER_URL}; skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "AGUI_INTEROP_TS_ENCODER_URL", matches = ".+")
class OfficialTypeScriptEncoderInteropTest {

    private static final String URL = System.getenv("AGUI_INTEROP_TS_ENCODER_URL");

    @Test
    void theOfficialEncoderAnswersAProtobufRequestWithFrames() throws Exception {
        HttpResponse<byte[]> resp = TestClient.HTTP.send(HttpRequest.newBuilder(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("Accept", AgUiProtocol.EVENT_PROTOBUF + ", " + AgUiProtocol.EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(Codec.input(
                        TestClient.input("t", "r", AgUiMessage.UserMessage.text("u", "hi")))))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(resp.headers().firstValue("Content-Type")).hasValue(AgUiProtocol.EVENT_PROTOBUF);
    }

    @Test
    void protoweftsClientReadsTheOfficialFrames() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        AgUiThread thread = AgUiThread.builder(AgUiClient.builder(URL).preferProtobuf(true).warnings(warnings::add).build())
                .listener(events::add).build();

        AgUiThread.Turn turn = thread.send("hello");

        assertThat(turn.status()).isEqualTo(AgUiThread.Status.COMPLETED);
        assertThat(turn.text()).isEqualTo("you said: hello");
        // Snapshot then delta, as the encoder agent sends them; null inside open JSON survives the wire.
        assertThat(thread.state()).isEqualTo(Json.parse(
                "{\"city\":\"Oslo\",\"temps\":[1,2.5],\"ok\":true,\"none\":null,\"temp\":21.5}"));
        assertThat(events).extracting(AgUiEvent::type).contains("TOOL_CALL_RESULT", "STATE_DELTA", "RUN_FINISHED");
        assertThat(warnings).isEmpty();
    }

    @Test
    void theOfficialEncoderPassesTheConformanceCheckerOverBothBindings() {
        AgUiConformance.Report report = AgUiConformance.builder(URI.create(URL)).build().run();
        assertThat(report.passed()).as(report.text()).isTrue();
        assertThat(report.results()).filteredOn(r -> r.id().startsWith("protobuf/") || r.id().startsWith("binding/protobuf"))
                .hasSize(7).allSatisfy(r -> assertThat(r.status()).as(r.id()).isEqualTo(AgUiConformance.Status.PASS));
    }
}
