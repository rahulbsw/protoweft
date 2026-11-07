package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Our client against our server, both on one Jetty host (callbacks on a mounted {@link RapCallbackEndpoint}). */
class RoundTripTest {

    private static final InboundAuthenticator BEARER = request -> request.bearerToken()
            .filter(t -> t.equals("good"))
            .<AuthDecision>map(t -> AuthDecision.allow(CallerIdentity.of("runtime-a")))
            .orElse(AuthDecision.unauthenticated("token required"));

    private final TestTools tools = new TestTools();
    private RapToolServer rap;
    private ProtoweftServer server;
    private RapCallbackEndpoint callbacks;

    @BeforeEach
    void setUp() {
        AtomicReference<ProtoweftServer> host = new AtomicReference<>();
        callbacks = RapCallbackEndpoint.create(() -> host.get().baseUrl());
        rap = RapToolServer.builder(LocalTools.builder().scan(tools).build()).name("round-trip").build();
        server = ProtoweftServer.builder().port(0).endpoints(rap, callbacks)
                .authenticator(RapCallbackEndpoint.exemptCallbacks(BEARER)).start();
        host.set(server);
    }

    @AfterEach
    void tearDown() {
        server.close();
        rap.close();
    }

    private RapClientConnector client(RapPromptHandler prompts) {
        RapClientConnector.Builder b = RapClientConnector.builder(server.baseUrl()).callbacks(callbacks)
                .credentials((protocol, target) -> List.of(new Credential.BearerToken("good")));
        if (prompts != null) {
            b.promptHandler(prompts);
        }
        RapClientConnector c = b.build();
        c.start();
        return c;
    }

    @Test
    void resultsContentErrorsAndUserIdentity() {
        try (RapClientConnector c = client(null)) {
            assertThat(c.invoke(ToolInvocation.of("echo", Map.of("text", "hi"))).text()).isEqualTo("hi");
            assertThat(c.invoke(ToolInvocation.of("image", Map.of())).content())
                    .containsExactly(new Content.Text("A pixel:"), new Content.Data("image/png", "iVBORw0KGgo="));
            ToolOutcome failed = c.invoke(ToolInvocation.of("fail", Map.of()));
            assertThat(failed.error()).isTrue();
            assertThat(failed.text()).isEqualTo("Error: database down");
            assertThat(c.invoke(ToolInvocation.of("whoami", Map.of()), CallerIdentity.of("alice")).text())
                    .isEqualTo("runtime-a/alice/alice");
            assertThat(c.invoke(ToolInvocation.of("probe", Map.of())).text()).isEqualTo("rap");
        }
    }

    @Test
    void unauthenticatedClientIsRejected() {
        try (RapClientConnector c = RapClientConnector.builder(server.baseUrl()).callbacks(callbacks).build()) {
            assertThatThrownBy(c::start).satisfies(e -> assertThat(((io.github.rahulbsw.protoweft.core.ProtoweftException) e)
                    .category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
    }

    @Test
    void subscriptionEventsStreamAndFinalCompletes() throws Exception {
        try (RapClientConnector c = client(null)) {
            ToolOutcome out = c.invoke(ToolInvocation.of("watch", Map.of()));
            assertThat(out.text()).isEqualTo("subscribed");
            ToolSubscription sub = out.subscription().orElseThrow();
            EventCollector events = new EventCollector();
            sub.events().subscribe(events);
            RapSubscription serverSide = tools.subscriptions.poll(5, TimeUnit.SECONDS);
            serverSide.emit("second", true);
            serverSide.close("bye", false);
            events.awaitDone();
            assertThat(events.texts()).containsExactly("first", "second", "bye");
            ToolEvent second = events.events.get(1);
            assertThat(second.metadata()).containsEntry("associative", true).containsEntry("final", false);
            assertThat(events.events.get(2).last()).isTrue();
            assertThat(sub.isActive()).isFalse();
        }
    }

    @Test
    void clientCancelStopsTheServerSubscription() throws Exception {
        try (RapClientConnector c = client(null)) {
            ToolSubscription sub = c.invoke(ToolInvocation.of("watch", Map.of())).subscription().orElseThrow();
            RapSubscription serverSide = tools.subscriptions.poll(5, TimeUnit.SECONDS);
            EventCollector events = new EventCollector();
            sub.events().subscribe(events);
            events.awaitCount(1);
            sub.cancel();
            assertThat(tools.cancelled.poll(5, TimeUnit.SECONDS)).isEqualTo(sub.id());
            assertThat(serverSide.isActive()).isFalse();
            assertThat(serverSide.emit("too late")).isFalse();
            events.awaitDone();
            assertThat(events.texts()).containsExactly("first");
        }
    }

    @Test
    void oauthWithAndWithoutPromptHandler() throws Exception {
        ClientPromptTest.RecordingHandler handler = new ClientPromptTest.RecordingHandler(0);
        try (RapClientConnector c = client(handler)) {
            assertThat(c.invoke(ToolInvocation.of("login", Map.of())).text()).isEqualTo("authorized");
            assertThat(handler.seen.poll(5, TimeUnit.SECONDS)).isEqualTo("auth:https://auth.example/authorize?state=s:login");
        }
        BlockingQueue<RapRecoveredResult> recovered = new LinkedBlockingQueue<>();
        try (RapClientConnector c = client(null)) {
            c.onRecoveredResult(recovered::add);
            assertThatThrownBy(() -> c.invoke(ToolInvocation.of("login", Map.of())))
                    .isInstanceOf(RapAuthorizationRequiredException.class)
                    .hasMessageContaining("https://auth.example/authorize?state=s");
            assertThat(recovered.poll(5, TimeUnit.SECONDS).outcome().text()).isEqualTo("authorized");
        }
    }

    @Test
    void userChoiceWithAndWithoutPromptHandler() {
        try (RapClientConnector c = client(new ClientPromptTest.RecordingHandler(0))) {
            assertThat(c.invoke(ToolInvocation.of("ask", Map.of())).text()).isEqualTo("chose 0");
        }
        try (RapClientConnector c = client(null)) {
            assertThat(c.invoke(ToolInvocation.of("ask", Map.of())).text()).isEqualTo("chose 1");
        }
    }

    @Test
    void threadClosureEndsTheThreadsSubscriptions() throws Exception {
        try (RapClientConnector c = client(null)) {
            RapThread thread = c.onThread("conversation-9");
            ToolSubscription sub = thread.invoke(ToolInvocation.of("watch", Map.of())).subscription().orElseThrow();
            EventCollector events = new EventCollector();
            sub.events().subscribe(events);
            thread.close().get(5, TimeUnit.SECONDS);
            events.awaitDone();
            assertThat(events.events.get(events.events.size() - 1).last()).isTrue();
            assertThat(tools.cancelled.poll(5, TimeUnit.SECONDS)).isEqualTo(sub.id());
        }
    }

    @Test
    void aReservedRapConnectorBridgesSubscriptionsAcrossTwoHops() throws Exception {
        try (RapClientConnector upstream = client(null);
             RapToolServer proxy = RapToolServer.builder(upstream).name("proxy").build();
             RapCallbackListener listener = RapCallbackListener.builder().start();
             ProtoweftServer proxyHost = ProtoweftServer.builder().port(0).endpoints(proxy).start();
             RapClientConnector downstream = RapClientConnector.builder(proxyHost.baseUrl()).callbacks(listener)
                     .timeout(Duration.ofSeconds(10)).build()) {
            downstream.start();
            ToolSubscription sub = downstream.invoke(ToolInvocation.of("watch", Map.of())).subscription().orElseThrow();
            EventCollector events = new EventCollector();
            sub.events().subscribe(events);
            RapSubscription origin = tools.subscriptions.poll(5, TimeUnit.SECONDS);
            origin.close("end", true);
            events.awaitDone();
            assertThat(events.texts()).containsExactly("first", "end");
            assertThat(events.events.get(1).metadata()).containsEntry("associative", true);
        }
    }
}
