package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AgUiConformanceTest {

    /** A producer that answers every POST with whatever {@code script} makes of the request body. */
    static final class Producer extends HttpServlet {
        volatile Function<Map<?, ?>, String> script = in -> "";
        /** When set, a request whose Accept names the binary binding gets these bytes with this type. */
        volatile Function<Map<?, ?>, byte[]> binary;
        volatile String binaryType = AgUiProtocol.EVENT_PROTOBUF;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Object parsed;
            try {
                parsed = Json.parse(body);
            } catch (AgUiFormatException e) {
                parsed = null;
            }
            if (!(parsed instanceof Map<?, ?> in) || !(in.get("messages") instanceof List)) {
                resp.setStatus(400);
                return;
            }
            resp.setStatus(200);
            String accept = req.getHeader("Accept");
            if (binary != null && accept != null && accept.contains(AgUiProtocol.EVENT_PROTOBUF)) {
                resp.setHeader("Content-Type", binaryType);
                resp.getOutputStream().write(binary.apply(in));
                return;
            }
            resp.setContentType("text/event-stream");
            resp.getOutputStream().write(script.apply(in).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final Producer producer = new Producer();
    private ProtoweftServer host;
    private AgUiAgentServer agui;

    @BeforeEach
    void start() {
        agui = AgUiAgentServer.builder(AgUiThreadTest.AGENT).build();
        host = ProtoweftServer.builder().port(0).endpoints(agui)
                .endpoint(new ServletEndpoint("producer", ProtocolId.AGUI, List.of("/producer"), producer, false))
                .start();
    }

    @AfterEach
    void stop() {
        host.close();
        agui.close();
    }

    private AgUiConformance.Report check(String path) {
        return AgUiConformance.builder(URI.create(host.baseUrl() + path)).timeout(Duration.ofSeconds(10)).build().run();
    }

    private static String frames(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String e : events) {
            sb.append("data: ").append(e).append("\n\n");
        }
        return sb.toString();
    }

    private static String started(Map<?, ?> in, String version) {
        return "{\"type\":\"RUN_STARTED\",\"threadId\":\"" + in.get("threadId") + "\",\"runId\":\"" + in.get("runId") + "\""
                + (version == null ? "" : ",\"protocolVersion\":\"" + version + "\"") + "}";
    }

    private static String finished(Map<?, ?> in) {
        return "{\"type\":\"RUN_FINISHED\",\"threadId\":\"" + in.get("threadId") + "\",\"runId\":\"" + in.get("runId") + "\"}";
    }

    private static List<String> failed(AgUiConformance.Report report) {
        return report.failures().stream().map(AgUiConformance.Result::id).toList();
    }

    @Test
    void aProtoweftServerPassesEveryCheck() {
        AgUiConformance.Report report = check("/agui");
        assertThat(report.passed()).as(report.text()).isTrue();
        assertThat(report.results()).hasSize(19);
        assertThat(report.results()).allSatisfy(r -> assertThat(r.status()).as(r.id()).isEqualTo(AgUiConformance.Status.PASS));
        assertThat(report.results()).extracting(AgUiConformance.Result::id)
                .contains("binding/protobuf-content-type", "binding/protobuf-frames", "protobuf/lifecycle/identity");
        assertThat(report.json()).contains("\"status\":\"PASS\"");
    }

    @Test
    void anAgentWithoutTheBinaryBindingSkipsItsChecksAndStillPasses() {
        AgUiAgentServer sseOnly = AgUiAgentServer.builder(AgUiThreadTest.AGENT).path("/sse-only").protobuf(false).build();
        try (ProtoweftServer other = ProtoweftServer.builder().port(0).endpoints(sseOnly).start()) {
            AgUiConformance.Report report = AgUiConformance.builder(URI.create(other.baseUrl() + "/sse-only"))
                    .timeout(Duration.ofSeconds(10)).build().run();
            assertThat(report.passed()).as(report.text()).isTrue();
            assertThat(status(report, "binding/protobuf-frames")).isEqualTo(AgUiConformance.Status.SKIP);
            assertThat(report.results().stream().filter(r -> r.status() == AgUiConformance.Status.SKIP)).hasSize(7);
        } finally {
            sseOnly.close();
        }
    }

    @Test
    void binaryViolationsAreCaught() {
        producer.script = in -> frames(started(in, "1.0"), finished(in));
        producer.binary = in -> {
            byte[] good = ProtoBinding.frame(Map.of("type", "RUN_STARTED", "threadId", in.get("threadId"),
                    "runId", in.get("runId"), "protocolVersion", "1.0"));
            return java.util.Arrays.copyOf(good, good.length + 3);   // then a cut-off frame header
        };
        producer.binaryType = AgUiProtocol.EVENT_PROTOBUF + "; charset=utf-8";
        assertThat(failed(check("/producer"))).contains("binding/protobuf-content-type", "binding/protobuf-frames",
                "protobuf/lifecycle/terminal").doesNotContain("lifecycle/terminal");

        producer.binaryType = AgUiProtocol.EVENT_PROTOBUF;
        producer.binary = in -> new byte[] {0, 0, 0, 2, (byte) 0xff, (byte) 0xff};
        AgUiConformance.Report garbage = check("/producer");
        assertThat(failed(garbage)).contains("binding/protobuf-frames").doesNotContain("binding/protobuf-content-type");
    }

    @Test
    void aConformingHandWrittenProducerWithChunksPasses() {
        producer.script = in -> frames(started(in, "1.0"),
                "{\"type\":\"TEXT_MESSAGE_CHUNK\",\"messageId\":\"m\",\"delta\":\"hi\"}", finished(in));
        AgUiConformance.Report report = check("/producer");
        // The scripted producer accepts anything with a messages array, so only the rejection
        // checks it does not implement may fail.
        assertThat(failed(report)).doesNotContain("events/described-shapes", "lifecycle/sequence", "binding/lf-framing");
    }

    @Test
    void eachKindOfViolationIsCaught() {
        producer.script = in -> frames(started(in, null),
                "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m\",\"role\":null,\"sparkle\":1}",
                "{\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"other\",\"delta\":\"x\"}").replace("\n\n", "\r\n\r\n");
        assertThat(failed(check("/producer"))).contains("binding/lf-framing", "events/described-shapes",
                "lifecycle/sequence", "lifecycle/terminal", "version/declared");
    }

    private static AgUiConformance.Status status(AgUiConformance.Report report, String id) {
        return report.results().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow().status();
    }

    @Test
    void aRunThatFailsBeforeItStartsIsNotAViolation() {
        producer.script = in -> frames("{\"type\":\"RUN_ERROR\",\"message\":\"model unavailable\"}");
        AgUiConformance.Report report = check("/producer");
        assertThat(status(report, "lifecycle/identity")).isEqualTo(AgUiConformance.Status.SKIP);
        assertThat(status(report, "version/declared")).isEqualTo(AgUiConformance.Status.SKIP);
        assertThat(status(report, "lifecycle/sequence")).isEqualTo(AgUiConformance.Status.PASS);
        assertThat(status(report, "lifecycle/terminal")).isEqualTo(AgUiConformance.Status.PASS);
        assertThat(report.text()).contains("SKIP  lifecycle/identity", "skipped");
    }

    @Test
    void everyRunBoundaryIsChecked() {
        // A whole run for another conversation, then the requested one: not acceptable.
        producer.script = in -> frames(
                "{\"type\":\"RUN_STARTED\",\"threadId\":\"someone-else\",\"runId\":\"x\",\"protocolVersion\":\"1.0\"}",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"someone-else\",\"runId\":\"x\"}",
                started(in, "1.0"), finished(in));
        AgUiConformance.Report foreign = check("/producer");
        assertThat(failed(foreign)).contains("lifecycle/identity");
        assertThat(foreign.failures().stream().filter(r -> r.id().equals("lifecycle/identity")).findFirst().orElseThrow()
                .detail()).contains("someone-else");

        // A replayed earlier run of the same thread, then the requested one: what the spec allows.
        producer.script = in -> frames(
                "{\"type\":\"RUN_STARTED\",\"threadId\":\"" + in.get("threadId") + "\",\"runId\":\"earlier\","
                        + "\"protocolVersion\":\"1.0\"}",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"" + in.get("threadId") + "\",\"runId\":\"earlier\"}",
                started(in, "1.0"), finished(in));
        assertThat(status(check("/producer"), "lifecycle/identity")).isEqualTo(AgUiConformance.Status.PASS);

        // An earlier run reusing the requested runId is refused.
        producer.script = in -> frames(started(in, "1.0"), finished(in), started(in, "1.0"), finished(in));
        assertThat(failed(check("/producer"))).contains("lifecycle/identity");
    }

    @Test
    void wrongIdentityAndBadFramesAreCaught() {
        producer.script = in -> frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"elsewhere\",\"runId\":\"r\","
                        + "\"protocolVersion\":\"1.0\"}", "not json",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"elsewhere\",\"runId\":\"r\"}");
        assertThat(failed(check("/producer"))).contains("lifecycle/identity", "binding/one-event-per-frame");
    }
}
