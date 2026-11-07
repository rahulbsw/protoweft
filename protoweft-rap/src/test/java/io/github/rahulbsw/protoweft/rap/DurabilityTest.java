package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Restarts of either side with {@link FileRapStore}s. */
class DurabilityTest {

    @TempDir
    Path clientDir;
    @TempDir
    Path serverDir;

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static RapClientConnector client(String serverUrl, RapCallbackListener listener, Path dir) {
        return RapClientConnector.builder(serverUrl).callbacks(listener).store(new FileRapStore(dir))
                .storeKey("agent-1").build();
    }

    @Test
    void clientRestartReceivesALateResultThroughTheRecoveryListener() throws Exception {
        TestTools tools = new TestTools();
        int port = freePort();
        try (RapToolServer rap = RapToolServer.builder(LocalTools.builder().scan(tools).build()).build();
             ProtoweftServer host = ProtoweftServer.builder().port(0).endpoints(rap).start()) {
            RapCallbackListener listener = RapCallbackListener.builder().port(port).start();
            RapClientConnector before = client(host.baseUrl(), listener, clientDir);
            before.start();
            CompletableFuture<ToolOutcome> lost = before.invokeAsync(ToolInvocation.of("hold", Map.of()));
            Thread.sleep(200);
            before.close();
            listener.close();
            assertThat(lost).isCompletedExceptionally();
            assertThat(new FileRapStore(clientDir).calls()).singleElement()
                    .satisfies(c -> assertThat(c.operation()).isEqualTo("hold"));

            BlockingQueue<RapRecoveredResult> recovered = new LinkedBlockingQueue<>();
            try (RapCallbackListener again = RapCallbackListener.builder().port(port).start();
                 RapClientConnector after = RapClientConnector.builder(host.baseUrl()).callbacks(again)
                         .store(new FileRapStore(clientDir)).storeKey("agent-1").onRecoveredResult(recovered::add)
                         .build()) {
                tools.release.countDown();
                RapRecoveredResult r = recovered.poll(10, TimeUnit.SECONDS);
                assertThat(r).isNotNull();
                assertThat(r.call().operation()).isEqualTo("hold");
                assertThat(r.outcome().text()).isEqualTo("late result");
                assertThat(new FileRapStore(clientDir).calls()).isEmpty();
            }
        }
    }

    @Test
    void bothSidesRestartAndASubscriptionResumes() throws Exception {
        int clientPort = freePort();
        TestTools tools = new TestTools();
        RapToolServer rap1 = RapToolServer.builder(LocalTools.builder().scan(tools).build())
                .store(new FileRapStore(serverDir)).build();
        ProtoweftServer host1 = ProtoweftServer.builder().port(0).endpoints(rap1).start();
        RapCallbackListener listener1 = RapCallbackListener.builder().port(clientPort).start();
        RapClientConnector client1 = client(host1.baseUrl(), listener1, clientDir);
        client1.start();
        ToolSubscription sub1 = client1.invoke(ToolInvocation.of("watch", Map.of())).subscription().orElseThrow();
        EventCollector first = new EventCollector();
        sub1.events().subscribe(first);
        first.awaitCount(1);
        String callId = sub1.id();

        client1.close();
        listener1.close();
        host1.close();
        rap1.close();

        List<RapStore.ServerSubscription> stored = new FileRapStore(serverDir).subscriptions();
        assertThat(stored).singleElement().satisfies(s -> assertThat(s.toolCallId()).isEqualTo(callId));
        assertThat(new FileRapStore(clientDir).calls()).singleElement().satisfies(c -> assertThat(c.subscription()).isTrue());

        TestTools tools2 = new TestTools();
        try (RapToolServer rap2 = RapToolServer.builder(LocalTools.builder().scan(tools2).build())
                .store(new FileRapStore(serverDir)).build();
             ProtoweftServer host2 = ProtoweftServer.builder().port(0).endpoints(rap2).start();
             RapCallbackListener listener2 = RapCallbackListener.builder().port(clientPort).start();
             RapClientConnector client2 = client(host2.baseUrl(), listener2, clientDir)) {
            assertThat(rap2.recoveredSubscriptions()).singleElement()
                    .satisfies(s -> assertThat(s.toolCallId()).isEqualTo(callId));
            assertThat(client2.recoveredSubscriptions()).singleElement()
                    .satisfies(r -> assertThat(r.call().toolCallId()).isEqualTo(callId));
            RapSubscription resumed = rap2.recoveredSubscriptions().get(0);
            BlockingQueue<String> serverCancelled = new LinkedBlockingQueue<>();
            resumed.onCancel(() -> serverCancelled.add("cancelled"));
            ToolSubscription sub2 = client2.recoveredSubscriptions().get(0).subscription();
            EventCollector events = new EventCollector();
            sub2.events().subscribe(events);

            assertThat(resumed.emit("after restart", true)).isTrue();
            events.awaitCount(1);
            assertThat(events.texts()).containsExactly("after restart");

            sub2.cancel();
            assertThat(serverCancelled.poll(10, TimeUnit.SECONDS)).isEqualTo("cancelled");
            events.awaitDone();
            assertThat(new FileRapStore(serverDir).subscriptions()).isEmpty();
            assertThat(new FileRapStore(clientDir).calls()).isEmpty();
        }
    }

    @Test
    void fileStorePersistsAcrossInstancesAndPurges() {
        FileRapStore a = new FileRapStore(clientDir.resolve("nested"));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        a.saveCall(new RapStore.ClientCall("o", "tok", "http://s", "th", "id", "op", t0, false));
        a.saveSubscription(new RapStore.ServerSubscription("srv", "th", "id", "op", "https://cb", "caller", null, t0));
        assertThat(a.markProcessed("k1", t0)).isTrue();
        assertThat(a.markProcessed("k1", t0)).isFalse();

        FileRapStore b = new FileRapStore(clientDir.resolve("nested"));
        assertThat(b.calls()).containsExactly(new RapStore.ClientCall("o", "tok", "http://s", "th", "id", "op", t0, false));
        assertThat(b.subscriptions()).singleElement().satisfies(s -> assertThat(s.userId()).isNull());
        assertThat(b.isProcessed("k1")).isTrue();
        b.purgeProcessed(t0.plusSeconds(1));
        assertThat(a.isProcessed("k1")).isFalse();
        b.deleteCall("tok");
        b.deleteSubscription("srv", "th", "id");
        assertThat(a.calls()).isEmpty();
        assertThat(a.subscriptions()).isEmpty();
    }

    @Test
    void fileStoreSurvivesConcurrentWriters() throws Exception {
        FileRapStore a = new FileRapStore(serverDir);
        FileRapStore b = new FileRapStore(serverDir);
        List<CompletableFuture<Void>> writers = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            writers.add(CompletableFuture.runAsync(() -> (n % 2 == 0 ? a : b).saveCall(new RapStore.ClientCall("o",
                    "t" + n, "http://s", "th", "id" + n, "op", Instant.now(), false))));
        }
        CompletableFuture.allOf(writers.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertThat(a.calls()).hasSize(20);
    }
}
