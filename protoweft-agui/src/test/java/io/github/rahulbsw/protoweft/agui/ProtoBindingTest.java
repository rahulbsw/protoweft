package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP + Protobuf binding end to end: negotiation, framing, and the client reading either binding. */
class ProtoBindingTest {

    private static final String PROTO = AgUiProtocol.EVENT_PROTOBUF;
    private static final String SSE = AgUiProtocol.EVENT_STREAM;

    /** A run using several families, so both bindings carry text, tools, state and a subagent. */
    private static final AgUiAgent AGENT = (in, run) -> {
        run.say("Hello " + in.threadId());
        String call = run.toolCall("search", "{\"q\":\"oslo\"}");
        run.toolResult(call, "21.5");
        run.stateSnapshot(Map.of("city", "Oslo", "temps", List.of(1, 2.5)));
        run.stateDelta(List.of(JsonPatch.op("add", "/temp", 21.5)));
        AgUiRun.Subagent helper = run.startSubagent("writer", "writes");
        helper.say("done");
        helper.finish(null);
        run.finish();
    };

    private ProtoweftServer host;
    private AgUiAgentServer agui;

    private String start(AgUiAgentServer.Builder builder) {
        agui = builder.build();
        host = ProtoweftServer.builder().port(0).endpoints(agui).start();
        return host.baseUrl() + "/agui";
    }

    @AfterEach
    void stop() {
        if (host != null) {
            host.close();
        }
        if (agui != null) {
            agui.close();
        }
    }

    @Test
    void negotiationFollowsTheSpec() {
        assertThat(AgUiServlet.binding(PROTO + ", " + SSE, true)).isEqualTo(PROTO);
        assertThat(AgUiServlet.binding(SSE + ", " + PROTO + ";q=0.1", true)).as("q-values are not compared").isEqualTo(PROTO);
        assertThat(AgUiServlet.binding("*/*", true)).as("a wildcard admits it").isEqualTo(PROTO);
        assertThat(AgUiServlet.binding("application/*", true)).isEqualTo(PROTO);
        assertThat(AgUiServlet.binding(SSE, true)).isEqualTo(SSE);
        assertThat(AgUiServlet.binding(null, true)).as("no Accept, as from the official encoder").isEqualTo(SSE);
        assertThat(AgUiServlet.binding("*/*, " + PROTO + ";q=0", true)).as("the specific range wins").isEqualTo(SSE);
        assertThat(AgUiServlet.binding(PROTO + ";q=0, " + SSE, true)).isEqualTo(SSE);
        assertThat(AgUiServlet.binding(PROTO, true)).isEqualTo(PROTO);
        assertThat(AgUiServlet.binding(PROTO, false)).as("binding off, nothing acceptable").isNull();
        assertThat(AgUiServlet.binding("*/*", false)).isEqualTo(SSE);
        assertThat(AgUiServlet.binding("application/json", true)).isNull();
        assertThat(AgUiServlet.binding(SSE + ";q=0", true)).isNull();
    }

    @Test
    void theServerAnswersFramesCarryingTheSameRunAsSse() throws Exception {
        String url = start(AgUiAgentServer.builder(AGENT));
        RunAgentInput input = TestClient.input("t1", "r1", UserMessage.text("u1", "hi"));

        HttpResponse<byte[]> binary = post(url, input, PROTO + ", " + SSE);
        assertThat(binary.statusCode()).isEqualTo(200);
        assertThat(binary.headers().firstValue("Content-Type")).hasValue(PROTO);
        assertThat(binary.headers().firstValue("Vary")).hasValue("Accept");
        List<AgUiEvent> fromFrames = new ArrayList<>();
        ProtoBinding.FrameReader frames = new ProtoBinding.FrameReader(new ByteArrayInputStream(binary.body()), 1 << 20);
        ConsumerPipeline pipeline = new ConsumerPipeline(new ArrayList<>());
        byte[] frame;
        while ((frame = frames.next()) != null) {
            fromFrames.addAll(pipeline.acceptFrame(frame));
        }

        TestClient.Response sse = TestClient.post(url, input, "Accept", SSE);
        assertThat(sse.headers().get("Content-Type").get(0)).startsWith(SSE);
        assertThat(stripIds(fromFrames)).isEqualTo(stripIds(sse.events()));
        assertThat(fromFrames.get(fromFrames.size() - 1)).isInstanceOf(AgUiEvent.RunFinished.class);
    }

    @Test
    void theClientReadsWhicheverBindingTheAgentAnswersWith() {
        String url = start(AgUiAgentServer.builder(AGENT));
        RunAgentInput input = TestClient.input("t1", "r1", UserMessage.text("u1", "hi"));
        List<AgUiEvent> viaProto = new ArrayList<>();
        RunResult proto = AgUiClient.builder(url).preferProtobuf(true).build().run(input, viaProto::add);
        List<AgUiEvent> viaSse = new ArrayList<>();
        RunResult sse = AgUiClient.builder(url).build().run(input, viaSse::add);

        assertThat(proto.finished()).isNotNull();
        assertThat(proto.warnings()).isEmpty();
        assertThat(stripIds(viaProto)).isEqualTo(stripIds(viaSse));
        assertThat(sse.finished()).isNotNull();
    }

    @Test
    void turnedOffTheServerAlwaysAnswersSseAndSaysSo() throws Exception {
        String url = start(AgUiAgentServer.builder(AGENT).protobuf(false));
        RunAgentInput input = TestClient.input("t1", "r1", UserMessage.text("u1", "hi"));
        assertThat(post(url, input, "*/*").headers().firstValue("Content-Type").orElseThrow()).startsWith(SSE);
        assertThat(post(url, input, PROTO).statusCode()).isEqualTo(406);
        assertThat(Json.parse(TestClient.request("GET", url + "/capabilities").body()))
                .isEqualTo(Map.of("transport", Map.of("streaming", true, "httpBinary", false)));
        assertThat(agui.compliance().unsupported()).containsExactly(AgUiFeatures.BINDING_PROTOBUF);

        // A client that prefers Protobuf still gets its run, over SSE.
        List<AgUiEvent> events = new ArrayList<>();
        RunResult result = AgUiClient.builder(url).preferProtobuf(true).build().run(input, events::add);
        assertThat(result.finished()).isNotNull();
        assertThat(events).isNotEmpty();
    }

    private static HttpResponse<byte[]> post(String url, RunAgentInput input, String accept) throws Exception {
        return TestClient.HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(Codec.input(input)))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Events without the server-generated ids and timestamps, which differ between two runs. */
    private static List<String> stripIds(List<AgUiEvent> events) {
        return events.stream().map(e -> Json.write(EventCodec.encode(e))
                .replaceAll("\"(messageId|toolCallId|subagentRunId|parentMessageId)\":\"[^\"]*\"", "\"$1\":\"*\"")
                .replaceAll("\"timestamp\":\\d+", "\"timestamp\":0")).toList();
    }
}
