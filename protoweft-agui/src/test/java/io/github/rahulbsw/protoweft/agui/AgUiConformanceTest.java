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
        assertThat(report.results()).hasSize(12);
        assertThat(report.json()).contains("\"status\":\"PASS\"");
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

    @Test
    void wrongIdentityAndBadFramesAreCaught() {
        producer.script = in -> frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"elsewhere\",\"runId\":\"r\","
                        + "\"protocolVersion\":\"1.0\"}", "not json",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"elsewhere\",\"runId\":\"r\"}");
        assertThat(failed(check("/producer"))).contains("lifecycle/identity", "binding/one-event-per-frame");
    }
}
