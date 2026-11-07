package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: a tool whose own thread emits events while its result is still being delivered must
 * not have a later event (the final one) overtake earlier ones (subscription-events.md: events of
 * one subscription arrive in order). Seen as a lost-events race in the gateway example.
 */
class SubscriptionOrderingTest {

    public static class Burst {
        @AgentTool(description = "Subscribes and emits three events at once from another thread")
        public String burst(RapCallContext rap) {
            RapSubscription subscription = rap.subscribe();
            Thread producer = new Thread(() -> {
                subscription.emit("e1");
                subscription.emit("e2", true);
                subscription.close("e3", false);
            });
            producer.setDaemon(true);
            producer.start();
            return "burst";
        }
    }

    @Test
    void eventsEmittedDuringResultDeliveryKeepTheirOrder() throws Exception {
        AtomicReference<ProtoweftServer> host = new AtomicReference<>();
        RapCallbackEndpoint callbacks = RapCallbackEndpoint.create(() -> host.get().baseUrl());
        RapToolServer rap = RapToolServer.builder(LocalTools.builder().scan(new Burst()).build()).name("burst").build();
        try (ProtoweftServer server = ProtoweftServer.builder().port(0).endpoints(rap, callbacks).start()) {
            host.set(server);
            try (RapClientConnector client = RapClientConnector.builder(server.baseUrl()).callbacks(callbacks).build()) {
                client.start();
                for (int i = 0; i < 300; i++) {
                    ToolOutcome out = client.invoke(ToolInvocation.of("burst", Map.of()));
                    EventCollector events = new EventCollector();
                    out.subscription().orElseThrow().events().subscribe(events);
                    events.awaitDone();
                    assertThat(events.texts()).as("run %d", i).containsExactly("e1", "e2", "e3");
                }
            }
        } finally {
            rap.close();
        }
    }
}
