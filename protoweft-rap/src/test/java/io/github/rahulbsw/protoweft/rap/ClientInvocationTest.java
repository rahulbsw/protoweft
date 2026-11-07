package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientInvocationTest {

    private FakeToolServer fake;
    private RapCallbackListener listener;
    private RapClientConnector client;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeToolServer();
        listener = RapCallbackListener.builder().start();
        client = RapClientConnector.builder(fake.baseUrl()).callbacks(listener).threadId("thread-1")
                .retry(new RapRetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50))).build();
        client.start();
    }

    @AfterEach
    void tearDown() {
        client.close();
        listener.close();
        fake.close();
    }

    @Test
    void sendsTheInvocationAndMapsATextResult() throws Exception {
        fake.onInvoke = inv -> FakeToolServer.post((String) inv.get("callback_url"), FakeToolServer.result(inv, "hello"));
        ToolOutcome out = client.invoke(ToolInvocation.of("echo", Map.of("q", 1)), CallerIdentity.of("alice"));
        assertThat(out.error()).isFalse();
        assertThat(out.text()).isEqualTo("hello");
        assertThat(out.subscription()).isEmpty();
        Map<String, Object> inv = fake.nextInvocation();
        assertThat(inv).containsEntry("operation", "echo").containsEntry("arguments", Map.of("q", 1))
                .containsEntry("group_id", "thread-1").containsEntry("user_id", "alice").containsKey("call_id");
        assertThat((String) inv.get("callback_url")).startsWith(listener.baseUrl() + "/rap/callback/");
        String token = ((String) inv.get("callback_url")).substring((listener.baseUrl() + "/rap/callback/").length());
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
        assertThat(inv).doesNotContainKey("thread_ancestors");
    }

    @Test
    void contentSupersedesTextAndImagesBecomeData() {
        fake.onInvoke = inv -> FakeToolServer.post((String) inv.get("callback_url"), Map.of("type", "tool_result",
                "group_id", inv.get("group_id"), "id", inv.get("id"), "text", "ignored", "display_as",
                List.of(Map.of("type", "text", "content", "short")), "content", List.of(
                        Map.of("type", "text", "text", "An image:"),
                        Map.of("type", "image", "data", "iVBORw0KGgo=", "mediaType", "image/png"))));
        ToolOutcome out = client.invoke(ToolInvocation.of("echo", Map.of()));
        assertThat(out.content()).containsExactly(new Content.Text("An image:"), new Content.Data("image/png", "iVBORw0KGgo="));
    }

    @Test
    void errorPrefixMarksAFailure() {
        fake.onInvoke = inv -> FakeToolServer.post((String) inv.get("callback_url"),
                FakeToolServer.result(inv, "Error: API rate limit exceeded."));
        ToolOutcome out = client.invoke(ToolInvocation.of("echo", Map.of()));
        assertThat(out.error()).isTrue();
        assertThat(out.text()).isEqualTo("Error: API rate limit exceeded.");
    }

    @Test
    void nonOkAcknowledgementsMapToErrorsAndAuthentication() {
        fake.ackStatuses.add(202);
        ToolOutcome out = client.invoke(ToolInvocation.of("echo", Map.of()));
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("Error: ").contains("HTTP 202");
        fake.ackStatuses.add(400);
        assertThat(client.invoke(ToolInvocation.of("echo", Map.of())).text()).contains("HTTP 400");
        assertThat(fake.invocations).hasSize(2);
        fake.ackStatuses.add(403);
        assertThatThrownBy(() -> client.invoke(ToolInvocation.of("echo", Map.of())))
                .isInstanceOf(ProtoweftException.class)
                .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        assertThatThrownBy(() -> client.invoke(ToolInvocation.of("missing", Map.of())))
                .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));
    }

    @Test
    void serverErrorsAreRetriedWithBackoff() {
        fake.ackStatuses.add(503);
        fake.ackStatuses.add(500);
        fake.onInvoke = inv -> FakeToolServer.post((String) inv.get("callback_url"), FakeToolServer.result(inv, "ok"));
        assertThat(client.invoke(ToolInvocation.of("echo", Map.of())).text()).isEqualTo("ok");
        assertThat(fake.invocations).hasSize(3);
        fake.invocations.clear();
        fake.ackStatuses.add(502);
        fake.ackStatuses.add(502);
        fake.ackStatuses.add(502);
        assertThat(client.invoke(ToolInvocation.of("echo", Map.of())).text()).contains("HTTP 502");
        assertThat(fake.invocations).hasSize(3);
    }

    @Test
    void timeoutFailsWithTimeoutAndRequestsCancellation() throws Exception {
        try (RapClientConnector quick = RapClientConnector.builder(fake.baseUrl()).callbacks(listener)
                .timeout(Duration.ofMillis(300)).build()) {
            quick.start();
            assertThatThrownBy(() -> quick.invoke(ToolInvocation.of("echo", Map.of())))
                    .isInstanceOf(ProtoweftException.class)
                    .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.TIMEOUT));
            Map<String, Object> inv = fake.nextInvocation();
            assertThat(FakeToolServer.poll(fake.cancels)).isEqualTo(Map.of("thread_id", inv.get("group_id"),
                    "tool_call_id", inv.get("id")));
            // A result arriving after the cancellation is acknowledged and ignored.
            assertThat(FakeToolServer.post((String) inv.get("callback_url"), FakeToolServer.result(inv, "late")))
                    .isEqualTo(200);
        }
    }

    @Test
    void cancellingAnAsyncCallRequestsCancellation() throws Exception {
        CompletableFuture<ToolOutcome> future = client.invokeAsync(ToolInvocation.of("echo", Map.of()));
        Map<String, Object> inv = fake.nextInvocation();
        future.cancel(true);
        assertThat(FakeToolServer.poll(fake.cancels)).containsEntry("tool_call_id", inv.get("id"));
    }

    @Test
    void duplicateCallbacksAreProcessedOnce() throws Exception {
        CompletableFuture<ToolOutcome> future = client.invokeAsync(ToolInvocation.of("watch", Map.of()));
        Map<String, Object> inv = fake.nextInvocation();
        String url = (String) inv.get("callback_url");
        Map<String, Object> result = Map.of("type", "tool_result", "group_id", inv.get("group_id"), "id", inv.get("id"),
                "text", "subscribed", "subscription", true);
        assertThat(FakeToolServer.post(url, result)).isEqualTo(200);
        assertThat(FakeToolServer.post(url, result)).isEqualTo(200);
        ToolOutcome out = future.get(5, TimeUnit.SECONDS);
        EventCollector events = new EventCollector();
        out.subscription().orElseThrow().events().subscribe(events);
        FakeToolServer.post(url, Map.of("type", "subscription_event", "group_id", inv.get("group_id"),
                "tool_call_id", inv.get("id"), "text", "e1", "final", true));
        events.awaitDone();
        assertThat(events.texts()).containsExactly("e1");
    }

    @Test
    void forgedUnknownAndMismatchedCallbacksAreRejected() throws Exception {
        CompletableFuture<ToolOutcome> future = client.invokeAsync(ToolInvocation.of("echo", Map.of()));
        Map<String, Object> inv = fake.nextInvocation();
        String url = (String) inv.get("callback_url");
        String forged = listener.baseUrl() + "/rap/callback/" + Tokens.newCallbackToken();
        assertThat(FakeToolServer.post(forged, FakeToolServer.result(inv, "forged"))).isEqualTo(404);
        assertThat(FakeToolServer.post(listener.baseUrl() + "/rap/callback/short", FakeToolServer.result(inv, "x")))
                .isEqualTo(404);
        assertThat(FakeToolServer.post(url, Map.of("type", "tool_result", "group_id", "other-thread", "id", inv.get("id"),
                "text", "wrong thread"))).isEqualTo(404);
        assertThat(FakeToolServer.post(url, Map.of("type", "tool_result", "group_id", inv.get("group_id"), "id", "other",
                "text", "wrong id"))).isEqualTo(404);
        assertThat(FakeToolServer.post(url, "not json")).isEqualTo(400);
        assertThat(FakeToolServer.post(url, Map.of("type", "tool_result", "group_id", inv.get("group_id"),
                "id", inv.get("id")))).isEqualTo(400);
        assertThat(FakeToolServer.post(url, Map.of("type", "bogus", "group_id", inv.get("group_id"),
                "id", inv.get("id")))).isEqualTo(400);
        assertThat(FakeToolServer.post(url, Map.of("type", "view_update", "group_id", inv.get("group_id"),
                "view_type", "diff", "content", Map.of()))).isEqualTo(200);
        assertThat(future).isNotDone();
        assertThat(FakeToolServer.post(url, FakeToolServer.result(inv, "genuine"))).isEqualTo(200);
        assertThat(future.get(5, TimeUnit.SECONDS).text()).isEqualTo("genuine");
    }

    @Test
    void callbacksAreProcessedSeriallyPerThread() throws Exception {
        CompletableFuture<ToolOutcome> a = client.invokeAsync(ToolInvocation.of("watch", Map.of()));
        Map<String, Object> invA = fake.nextInvocation();
        CompletableFuture<ToolOutcome> b = client.invokeAsync(ToolInvocation.of("watch", Map.of()));
        Map<String, Object> invB = fake.nextInvocation();
        CompletableFuture<ToolOutcome> c = client.onThread("thread-2")
                .invokeAsync(ToolInvocation.of("watch", Map.of()), CallerIdentity.ANONYMOUS);
        Map<String, Object> invC = fake.nextInvocation();
        for (Map<String, Object> inv : List.of(invA, invB, invC)) {
            FakeToolServer.post((String) inv.get("callback_url"), Map.of("type", "tool_result", "group_id",
                    inv.get("group_id"), "id", inv.get("id"), "text", "ok", "subscription", true));
        }
        EventCollector blocking = new EventCollector();
        blocking.block();
        a.get(5, TimeUnit.SECONDS).subscription().orElseThrow().events().subscribe(blocking);
        b.get(5, TimeUnit.SECONDS).subscription().orElseThrow().events().subscribe(new EventCollector());
        c.get(5, TimeUnit.SECONDS).subscription().orElseThrow().events().subscribe(new EventCollector());

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> FakeToolServer.post(
                (String) invA.get("callback_url"), event(invA, "slow")));
        blocking.awaitEntered();
        CompletableFuture<Integer> sameThread = CompletableFuture.supplyAsync(() -> FakeToolServer.post(
                (String) invB.get("callback_url"), event(invB, "queued")));
        int otherThread = FakeToolServer.post((String) invC.get("callback_url"), event(invC, "free"));
        assertThat(otherThread).isEqualTo(200);
        Thread.sleep(300);
        assertThat(sameThread).isNotDone();
        blocking.release();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(sameThread.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    @Test
    void threadViewsSendTheirThreadAndAncestors() throws Exception {
        fake.onInvoke = inv -> FakeToolServer.post((String) inv.get("callback_url"), FakeToolServer.result(inv, "ok"));
        RapThread child = client.onThread("child", List.of("root", "parent"));
        assertThat(child.invoke(ToolInvocation.of("echo", Map.of())).text()).isEqualTo("ok");
        Map<String, Object> inv = fake.nextInvocation();
        assertThat(inv).containsEntry("group_id", "child").containsEntry("thread_ancestors", List.of("root", "parent"))
                .doesNotContainKey("user_id");
        child.close().get(5, TimeUnit.SECONDS);
        assertThat(FakeToolServer.poll(fake.closes)).isEqualTo(Map.of("thread_id", "child"));
        client.cancelToolCall("child", "x").get(5, TimeUnit.SECONDS);
        assertThat(FakeToolServer.poll(fake.cancels)).isEqualTo(Map.of("thread_id", "child", "tool_call_id", "x"));
    }

    private static Map<String, Object> event(Map<String, Object> inv, String text) {
        return Map.of("type", "subscription_event", "group_id", inv.get("group_id"), "tool_call_id", inv.get("id"),
                "text", text);
    }
}
