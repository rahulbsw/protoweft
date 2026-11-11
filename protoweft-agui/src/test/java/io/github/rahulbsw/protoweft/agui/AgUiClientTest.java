package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ActivityMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgUiClientTest {

    /** Answers every POST with a scripted status, content type and body; remembers the request. */
    static final class Scripted extends HttpServlet {
        volatile int status = 200;
        volatile String contentType = "text/event-stream";
        volatile String body = "";
        volatile long pauseMillis;
        volatile String lastRequest;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            lastRequest = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            resp.setStatus(status);
            resp.setContentType(contentType);
            for (String part : body.split("(?<=\n\n)")) {
                resp.getOutputStream().write(part.getBytes(StandardCharsets.UTF_8));
                resp.flushBuffer();
                if (pauseMillis > 0) {
                    try {
                        Thread.sleep(pauseMillis);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    private final Scripted scripted = new Scripted();
    private ProtoweftServer host;
    private AgUiAgentServer agui;

    @BeforeEach
    void start() {
        agui = AgUiAgentServer.builder((in, run) -> {
            run.say("echo: " + ((UserMessage) in.messages().get(in.messages().size() - 1)).content());
            run.finish(Map.of("ok", true));
        }).build();
        host = ProtoweftServer.builder().port(0).endpoints(agui)
                .endpoint(new ServletEndpoint("scripted", ProtocolId.AGUI, List.of("/scripted"), scripted, false))
                .start();
    }

    @AfterEach
    void stop() {
        host.close();
        agui.close();
    }

    private AgUiClient client(String path, List<String> warnings) {
        return AgUiClient.builder(host.baseUrl() + path).warnings(warnings::add).build();
    }

    private static RunAgentInput hello() {
        return RunAgentInput.of("t", "r", List.of(UserMessage.text("u1", "hi")));
    }

    private static String frames(String... json) {
        StringBuilder sb = new StringBuilder();
        for (String j : json) {
            sb.append("data: ").append(j).append("\n\n");
        }
        return sb.toString();
    }

    @Test
    void runsAgainstAProtoweftServer() {
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        RunResult result = client("/agui", new CopyOnWriteArrayList<>()).run(hello(), events::add);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.finished().result()).isEqualTo(Map.of("ok", true));
        assertThat(result.warnings()).isEmpty();
        assertThat(events).filteredOn(TextMessageContent.class::isInstance)
                .extracting(e -> ((TextMessageContent) e).delta()).containsExactly("echo: hi");
    }

    @Test
    void unknownMaterialIsDroppedChunksExpandedAndWarningsReported() {
        scripted.body = frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\",\"protocolVersion\":\"1.4\"}",
                "{\"type\":\"HOLOGRAM\",\"x\":1}",
                "{\"type\":\"TEXT_MESSAGE_CHUNK\",\"messageId\":\"m\",\"delta\":\"hi\",\"sparkle\":true}",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"t\",\"runId\":\"r\"}");
        List<String> warnings = new CopyOnWriteArrayList<>();
        List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        RunResult result = client("/scripted", warnings).run(hello(), events::add);
        assertThat(TestClient.types(events)).containsExactly("RUN_STARTED", "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
        assertThat(result.succeeded()).isTrue();
        assertThat(result.warnings()).hasSize(2).anyMatch(w -> w.contains("HOLOGRAM")).anyMatch(w -> w.contains("sparkle"));
        assertThat(warnings).isEqualTo(result.warnings());
    }

    @Test
    void outgoingInputStripsActivityAndDeclaresTheVersion() {
        scripted.body = frames("{\"type\":\"RUN_ERROR\",\"message\":\"no\"}");
        RunAgentInput in = new RunAgentInput("t", "r", null, null, null, List.of(UserMessage.text("u", "hi"),
                new ActivityMessage("a", "progress", Map.of(), null, null)), null, null, null, null);
        client("/scripted", new CopyOnWriteArrayList<>()).run(in, e -> { });
        Map<?, ?> sent = (Map<?, ?>) Json.parse(scripted.lastRequest);
        assertThat(sent.get("protocolVersion")).isEqualTo("1.0");
        assertThat((List<?>) sent.get("messages")).hasSize(1);
        assertThat(OfficialSchema.errors("RunAgentInput", sent)).isEmpty();
    }

    @Test
    void endings() {
        AgUiClient client = client("/scripted", new CopyOnWriteArrayList<>());
        scripted.body = frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}",
                "{\"type\":\"RUN_ERROR\",\"message\":\"model down\",\"code\":\"E\"}");
        RunResult failed = client.run(hello(), e -> { });
        assertThat(failed.error().message()).isEqualTo("model down");
        assertThat(failed.succeeded()).isFalse();

        scripted.body = frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}",
                "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m\"}");
        RunResult truncated = client.run(hello(), e -> { });
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.succeeded()).isFalse();
        assertThat(truncated.finished()).isNull();   // never a synthesized RUN_FINISHED

        scripted.body = frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}",
                "{\"type\":\"TOOL_CALL_START\",\"toolCallId\":\"c\",\"toolCallName\":\"confirm\"}",
                "{\"type\":\"TOOL_CALL_END\",\"toolCallId\":\"c\"}",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"t\",\"runId\":\"r\"}");
        RunResult pending = client.run(hello(), e -> { });
        assertThat(pending.pendingToolCalls()).containsExactly("c");   // derived when the agent did not state it
    }

    @Test
    void malformedStreamsAndRefusalsAreErrors() {
        AgUiClient client = client("/scripted", new CopyOnWriteArrayList<>());
        scripted.body = frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}",
                "{\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m\",\"delta\":\"orphan\"}");
        assertThatThrownBy(() -> client.run(hello(), e -> { })).isInstanceOfSatisfying(ProtoweftException.class,
                e -> assertThat(e.category()).isEqualTo(ErrorCategory.PROTOCOL));
        scripted.body = frames("{\"type\":\"TEXT_MESSAGE_END\",\"messageId\":7}");
        assertThatThrownBy(() -> client.run(hello(), e -> { })).isInstanceOf(ProtoweftException.class);

        scripted.status = 401;
        scripted.contentType = "application/json";
        scripted.body = "{\"error\":\"token required\"}";
        assertThatThrownBy(() -> client.run(hello(), e -> { })).isInstanceOfSatisfying(ProtoweftException.class,
                e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        scripted.status = 400;
        assertThatThrownBy(() -> client.run(hello(), e -> { })).isInstanceOfSatisfying(ProtoweftException.class,
                e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void cancellingAStreamTruncatesTheRun() throws Exception {
        StringBuilder body = new StringBuilder(frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}",
                "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m\"}"));
        for (int i = 0; i < 200; i++) {
            body.append(frames("{\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m\",\"delta\":\"x\"}"));
        }
        scripted.body = body.toString();
        scripted.pauseMillis = 20;
        AgUiClient.Call call = client("/scripted", new CopyOnWriteArrayList<>()).call(hello());
        CompletableFuture<RunResult> result = CompletableFuture.supplyAsync(() -> call.execute(e -> {
            if (e instanceof TextMessageContent) {
                call.cancel();
            }
        }));
        assertThat(result.get(10, TimeUnit.SECONDS).truncated()).isTrue();
    }

    @Test
    void publisherDeliversWithBackpressureAndCompletes() throws Exception {
        List<String> types = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        var executor = Executors.newCachedThreadPool();
        client("/agui", new CopyOnWriteArrayList<>()).publisher(hello(), executor).subscribe(new Flow.Subscriber<>() {
            Flow.Subscription s;

            public void onSubscribe(Flow.Subscription s) {
                this.s = s;
                s.request(1);
            }

            public void onNext(AgUiEvent item) {
                types.add(item.type());
                s.request(1);
            }

            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            public void onComplete() {
                done.complete(null);
            }
        });
        done.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(types).first().isEqualTo("RUN_STARTED");
        assertThat(types).last().isEqualTo("RUN_FINISHED");
    }

    @Test
    void aSingleThreadExecutorCannotDeadlockALongStream() throws Exception {
        StringBuilder body = new StringBuilder(frames("{\"type\":\"RUN_STARTED\",\"threadId\":\"t\",\"runId\":\"r\"}",
                "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m\"}"));
        for (int i = 0; i < 3 * Flow.defaultBufferSize(); i++) {
            body.append(frames("{\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m\",\"delta\":\"x\"}"));
        }
        body.append(frames("{\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m\"}",
                "{\"type\":\"RUN_FINISHED\",\"threadId\":\"t\",\"runId\":\"r\"}"));
        scripted.body = body.toString();
        var executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> done = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        client("/scripted", new CopyOnWriteArrayList<>()).publisher(hello(), executor).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            public void onNext(AgUiEvent item) {
                count.incrementAndGet();
            }

            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            public void onComplete() {
                done.complete(count.get());
            }
        });
        assertThat(done.get(20, TimeUnit.SECONDS)).isEqualTo(3 * Flow.defaultBufferSize() + 4);
        executor.shutdown();
    }

    @Test
    void cancellingTheSubscriptionClosesAQuietStream() throws Exception {
        stop();
        agui = AgUiAgentServer.builder((in, run) -> {
            run.say("thinking…");
            Thread.sleep(15_000);   // then nothing for a long time
        }).build();
        host = ProtoweftServer.builder().port(0).endpoints(agui).start();
        var executor = Executors.newCachedThreadPool();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        client("/agui", new CopyOnWriteArrayList<>()).publisher(hello(), executor).subscribe(new Flow.Subscriber<>() {
            Flow.Subscription s;

            public void onSubscribe(Flow.Subscription s) {
                this.s = s;
                s.request(Long.MAX_VALUE);
            }

            public void onNext(AgUiEvent item) {
                if (item instanceof AgUiEvent.TextMessageEnd) {
                    s.cancel();   // no further event is coming for a while
                    cancelled.complete(null);
                }
            }

            public void onError(Throwable t) {
            }

            public void onComplete() {
            }
        });
        cancelled.get(10, TimeUnit.SECONDS);
        // The reader was blocked on a silent stream; cancelling must close it now, not at the next event.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (readerAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(readerAlive()).isFalse();
        executor.shutdown();
    }

    private static boolean readerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().equals("protoweft-agui-reader"));
    }

    @Test
    void capabilitiesFromAProtoweftServer() {
        assertThat(client("/agui", List.of()).capabilities()).hasValueSatisfying(doc ->
                assertThat(doc).containsKey("transport"));
        assertThat(client("/scripted", List.of()).capabilities()).isEmpty();
    }
}
