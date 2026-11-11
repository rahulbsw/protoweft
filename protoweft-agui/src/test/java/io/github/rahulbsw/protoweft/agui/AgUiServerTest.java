package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.rahulbsw.protoweft.agui.TestClient.input;
import static io.github.rahulbsw.protoweft.agui.TestClient.post;
import static io.github.rahulbsw.protoweft.agui.TestClient.types;
import static org.assertj.core.api.Assertions.assertThat;

class AgUiServerTest {

    /** Admits callers with token "alice"; refuses everyone else. */
    static final InboundAuthenticator AUTH = request -> "alice".equals(request.bearerToken().orElse(""))
            ? AuthDecision.allow(CallerIdentity.of("alice")) : AuthDecision.unauthenticated("token required");

    private ProtoweftServer host;
    private AgUiAgentServer agui;

    private String start(AgUiAgentServer.Builder builder, InboundAuthenticator auth) {
        agui = builder.build();
        ProtoweftServer.Builder b = ProtoweftServer.builder().port(0).endpoints(agui);
        if (auth != null) {
            b.authenticator(auth);
        }
        host = b.start();
        return host.baseUrl() + "/agui";
    }

    private String start(AgUiAgent agent) {
        return start(AgUiAgentServer.builder(agent), null);
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
    void streamsEveryFamilyAsAConformingRun() throws Exception {
        String url = start((in, run) -> {
            run.startStep("plan");
            AgUiEmitter.ReasoningSpan thinking = run.startReasoning();
            AgUiEmitter.TextMessage thought = thinking.startMessage();
            thought.delta("Let me look.");
            thought.end();
            thinking.end();
            String call = run.toolCall("search", "{\"q\":\"oslo\"}");
            run.toolResult(call, "21.5");
            run.stateSnapshot(Map.of("city", "Oslo"));
            run.stateDelta(List.of(JsonPatch.op("add", "/temp", 21.5)));
            run.activity("a1", "search", Map.of("done", false));
            run.activityDelta("a1", "search", List.of(JsonPatch.op("replace", "/done", true)));
            AgUiRun.Subagent helper = run.startSubagent("writer", "writes the answer");
            helper.say("It is 21.5 degrees.");
            helper.finish(null);
            run.custom("acme.trace", Map.of("id", 1));
            run.raw(Map.of("vendor", "event"), "test");
            run.finishStep("plan");
            run.callClientTool("confirm", "{}");
            run.finish();
        });
        RunAgentInput in = new RunAgentInput("t1", "r1", "1.0", null, null,
                List.of(UserMessage.text("u1", "weather?")),
                List.of(new RunAgentInput.Tool("confirm", "Ask the user", null, null)), null, null, null);

        TestClient.Response resp = post(url, in);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.header("Content-Type")).startsWith("text/event-stream");
        assertThat(resp.header("Cache-Control")).isEqualTo("no-cache");
        List<AgUiEvent> events = resp.events();
        assertThat(events.get(0)).isEqualTo(new RunStarted("t1", "r1", "1.0", null, null, Envelope.NONE));
        assertThat(types(events)).contains("STEP_STARTED", "REASONING_MESSAGE_CONTENT", "TOOL_CALL_RESULT",
                "STATE_DELTA", "ACTIVITY_DELTA", "SUBAGENT_STARTED", "CUSTOM", "RAW", "SUBAGENT_FINISHED");
        TextMessageStart subagentText = events.stream().filter(TextMessageStart.class::isInstance)
                .map(TextMessageStart.class::cast).findFirst().orElseThrow();
        assertThat(subagentText.envelope().subagentRunId()).isNotNull();
        RunFinished finished = (RunFinished) events.get(events.size() - 1);
        ToolCallStart confirm = events.stream().filter(e -> e instanceof ToolCallStart s && s.toolCallName().equals("confirm"))
                .map(ToolCallStart.class::cast).findFirst().orElseThrow();
        assertThat(finished.outcome()).isEqualTo(new RunOutcome.Success(List.of(confirm.toolCallId())));
    }

    @Test
    void anAgentThatReturnsEarlyHasItsItemsClosedAndTheRunFinished() throws Exception {
        String url = start((in, run) -> {
            run.startStep("s");
            run.startMessage().delta("partial");
        });
        List<AgUiEvent> events = post(url, input("t", "r")).events();
        assertThat(types(events)).containsExactly("RUN_STARTED", "STEP_STARTED", "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "STEP_FINISHED", "RUN_FINISHED");
    }

    @Test
    void agentFailuresAndProtocolMisuseEndTheRunWithRunError() throws Exception {
        String url = start((in, run) -> {
            if (in.threadId().equals("throw")) {
                throw new IllegalStateException("model unavailable");
            }
            AgUiEmitter.TextMessage m = run.startMessage();
            m.end();
            m.delta("after end");   // breaks the streaming rules
        });
        List<AgUiEvent> thrown = post(url, input("throw", "r")).events();
        assertThat(thrown.get(thrown.size() - 1)).isEqualTo(new RunError("model unavailable", null, null, Envelope.NONE));
        List<AgUiEvent> misuse = post(url, input("misuse", "r")).events();
        assertThat(((RunError) misuse.get(misuse.size() - 1)).message()).contains("not open");
    }

    @Test
    void cancelClosesOpenItemsWithTheCancelledOutcome() throws Exception {
        String url = start((in, run) -> {
            run.startMessage().delta("stop here");
            run.cancel();
        });
        List<AgUiEvent> events = post(url, input("t", "r")).events();
        assertThat(((RunFinished) events.get(events.size() - 1)).outcome()).isInstanceOf(RunOutcome.Cancelled.class);
        assertThat(types(events)).contains("TEXT_MESSAGE_END");
    }

    @Test
    void inputIsRejectedBeforeTheRunStarts() throws Exception {
        String url = start(AgUiAgentServer.builder((in, run) -> run.finish()).maxRequestBytes(2_000), null);
        assertThat(post(url, (Object) "{not json").status()).isEqualTo(400);
        assertThat(post(url, (Object) Map.of("runId", "r", "messages", List.of())).status()).isEqualTo(400);
        assertThat(post(url, (Object) Map.of("threadId", 1, "runId", "r", "messages", List.of())).status()).isEqualTo(400);
        assertThat(post(url, (Object) Map.of("threadId", "t", "runId", "r", "messages", List.of(),
                "protocolVersion", "2.0")).status()).isEqualTo(400);
        assertThat(post(url, input("t", "r"), "Content-Type", "text/plain").status()).isEqualTo(415);
        assertThat(post(url, input("t", "r"), "Accept", "application/json").status()).isEqualTo(406);
        assertThat(post(url, input("t", "r", UserMessage.text("u", "x".repeat(5_000)))).status()).isEqualTo(413);
        TestClient.Response bad = post(url, (Object) "{not json");
        assertThat(bad.header("Content-Type")).startsWith("application/json");
        assertThat(bad.body()).contains("invalid run input");
        // A newer minor version and unknown members are served (with warnings), not rejected.
        TestClient.Response newer = post(url, (Object) Map.of("threadId", "t", "runId", "r", "messages", List.of(),
                "protocolVersion", "1.7", "futureField", true));
        assertThat(newer.status()).isEqualTo(200);
        assertThat(((RunStarted) newer.events().get(0)).protocolVersion()).isEqualTo("1.0");
    }

    @Test
    void capabilitiesDocument() throws Exception {
        String url = start(AgUiAgentServer.builder((in, run) -> run.finish())
                .capabilities(Map.of("identity", Map.of("name", "Weather"), "reasoning", Map.of("supported", true))), null);
        TestClient.Response resp = TestClient.request("GET", url + "/capabilities");
        assertThat(resp.status()).isEqualTo(200);
        Object doc = Json.parse(resp.body());
        assertThat(OfficialSchema.errors("AgentCapabilities", doc)).isEmpty();
        assertThat(doc).isEqualTo(Map.of("transport", Map.of("streaming", true, "httpBinary", false),
                "identity", Map.of("name", "Weather"), "reasoning", Map.of("supported", true)));
        assertThat(TestClient.request("GET", url).status()).isEqualTo(405);
    }

    @Test
    void corsIsOffByDefaultAndPreflightsPassAuthentication() throws Exception {
        String closed = start(AgUiAgentServer.builder((in, run) -> run.finish()), AUTH);
        assertThat(TestClient.request("OPTIONS", closed, "Origin", "https://app.test",
                "Access-Control-Request-Method", "POST").status()).isEqualTo(403);
        stop();

        String url = start(AgUiAgentServer.builder((in, run) -> {
            run.say("hello " + run.caller().name());
            run.finish();
        }).allowedOrigins("https://app.test"), AUTH);
        TestClient.Response preflight = TestClient.request("OPTIONS", url, "Origin", "https://app.test",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");
        assertThat(preflight.status()).isEqualTo(204);
        assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo("https://app.test");
        assertThat(preflight.header("Access-Control-Allow-Headers")).contains("Authorization");
        assertThat(TestClient.request("OPTIONS", url, "Origin", "https://evil.test",
                "Access-Control-Request-Method", "POST").status()).isEqualTo(403);

        TestClient.Response run = post(url, input("t", "r"), "Origin", "https://app.test", "Authorization", "Bearer alice");
        assertThat(run.header("Access-Control-Allow-Origin")).isEqualTo("https://app.test");
        assertThat(((TextMessageContent) run.events().get(2)).delta()).isEqualTo("hello alice");
        assertThat(post(url, input("t", "r"), "Origin", "https://app.test").status()).isEqualTo(401);
    }

    @Test
    void aClientThatDisconnectsCancelsTheRun() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String url = start((in, run) -> {
            AgUiEmitter.TextMessage m = run.startMessage();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!run.isCancelled() && System.nanoTime() < until) {
                m.delta("tick ");
                Thread.sleep(10);
            }
            if (run.isCancelled()) {
                cancelled.countDown();
            }
        });
        java.net.URI uri = java.net.URI.create(url);
        byte[] body = Json.writeBytes(Codec.input(input("t", "r")));
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + uri.getPath() + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[256];
            assertThat(in.read(buffer)).isPositive();   // the stream is flowing
        } catch (RuntimeException e) {
            failure.set(e);
        }
        assertThat(cancelled.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
    }

    @Test
    void complianceReport() {
        AgUiAgentServer server = AgUiAgentServer.builder((in, run) -> run.finish()).build();
        assertThat(server.compliance().protocol()).isEqualTo(ProtocolId.AGUI);
        assertThat(server.compliance().wireRevisions()).containsExactly("1.0");
        assertThat(server.compliance().supports(AgUiFeatures.BINDING_SSE)).isTrue();
        assertThat(server.compliance().unsupported()).containsExactly(AgUiFeatures.BINDING_PROTOBUF);
        server.close();
    }
}
