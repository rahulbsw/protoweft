package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ServerTest {

    private static final InboundAuthenticator BEARER = request -> request.bearerToken()
            .filter(t -> t.equals("good"))
            .<AuthDecision>map(t -> AuthDecision.allow(CallerIdentity.of("runtime-a")))
            .orElse(AuthDecision.unauthenticated("token required"));

    private final TestTools testTools = new TestTools();
    private FakeRuntime runtime;
    private InMemoryRapStore store;
    private RapToolServer rap;
    private ProtoweftServer server;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new FakeRuntime();
        store = new InMemoryRapStore();
        ToolRegistry registry = new ToolRegistry().register("local", LocalTools.builder().scan(testTools).build());
        rap = RapToolServer.builder(registry).name("test-tools").description("Test tools").store(store)
                .choiceTimeout(Duration.ofSeconds(5))
                .callbackRetry(new RapRetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50))).build();
        server = ProtoweftServer.builder().port(0).endpoints(rap).authenticator(BEARER).start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        rap.close();
        runtime.close();
    }

    private FakeRuntime.Response post(String path, Object body) {
        return FakeRuntime.send("POST", server.baseUrl() + path, body, "good");
    }

    private Map<String, Object> invoke(String operation, String id, Map<String, Object> args) throws Exception {
        assertThat(post("/invoke", runtime.invocation(operation, id, args)).status()).isEqualTo(200);
        return runtime.next();
    }

    @Test
    void servesTheToolsetWithRapSafeNames() {
        FakeRuntime.Response r = FakeRuntime.send("GET", server.baseUrl() + "/.well-known/rap-toolset", null, "good");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json()).containsEntry("name", "test-tools").containsEntry("description", "Test tools")
                .containsEntry("endpoint", server.baseUrl() + "/invoke");
        Map<String, Object> echo = r.tools().stream().filter(t -> t.get("name").equals("local_echo")).findFirst()
                .orElseThrow();
        assertThat(echo).containsEntry("description", "Echoes the text")
                .containsEntry("annotations", Map.of("x-protoweft-readOnly", true));
        assertThat(echo.get("inputSchema")).isInstanceOf(Map.class);
        assertThat(r.tools()).allSatisfy(t -> assertThat((String) t.get("name")).matches("[A-Za-z0-9_-]{1,128}"));
        assertThat(FakeRuntime.send("GET", server.baseUrl() + "/.well-known/rap-toolset", null, null).status())
                .isEqualTo(401);
    }

    @Test
    void acknowledgesThenDeliversTheResult() throws Exception {
        Map<String, Object> inv = runtime.invocation("local_echo", "call-1", Map.of("text", "hi"));
        inv.put("call_id", "provider-1");
        assertThat(post("/invoke", inv).status()).isEqualTo(200);
        assertThat(runtime.next()).isEqualTo(Map.of("type", "tool_result", "group_id", "thread-1", "id", "call-1",
                "call_id", "provider-1", "text", "hi"));
    }

    @Test
    void failuresUnknownOperationsAndBadArgumentsBecomeErrorResults() throws Exception {
        assertThat(invoke("local_fail", "c1", Map.of())).containsEntry("text", "Error: database down");
        assertThat(invoke("nope", "c2", Map.of())).containsEntry("text", "Error: unknown operation: nope");
        Map<String, Object> bad = runtime.invocation("local_echo", "c3", Map.of());
        bad.put("arguments", 5);
        assertThat(post("/invoke", bad).status()).isEqualTo(200);
        assertThat(runtime.next()).containsEntry("id", "c3").containsEntry("text", "Error: 'arguments' must be an object");
        assertThat(invoke("local_echo", "c4", Map.of()).get("text")).asString().startsWith("Error: missing required");
    }

    @Test
    void imagesTravelAsContent() throws Exception {
        assertThat(invoke("local_image", "c1", Map.of()).get("content")).isEqualTo(List.of(
                Map.of("type", "text", "text", "A pixel:"),
                Map.of("type", "image", "data", "iVBORw0KGgo=", "mediaType", "image/png")));
    }

    @Test
    void invocationsWithoutRoutingOrWithUnsafeCallbacksAreRejected() {
        Map<String, Object> noId = runtime.invocation("local_echo", "x", Map.of());
        noId.remove("id");
        assertThat(post("/invoke", noId).status()).isEqualTo(400);
        assertThat(post("/invoke", "[1]").status()).isEqualTo(400);
        for (String url : List.of("http://10.0.0.7/cb", "http://169.254.169.254/latest/meta-data",
                "https://169.254.169.254/x", "http://example.com/cb", "https://[fe80::1]/cb", "file:///etc/passwd")) {
            Map<String, Object> inv = runtime.invocation("local_echo", "x", Map.of("text", "t"));
            inv.put("callback_url", url);
            assertThat(post("/invoke", inv).status()).as(url).isEqualTo(400);
        }
        assertThat(RapCallbackPolicy.DEFAULT.allows(java.net.URI.create("https://agent.example.com/cb"))).isTrue();
        assertThat(RapCallbackPolicy.DEFAULT.allows(java.net.URI.create("http://127.0.0.2:9/cb"))).isTrue();
        assertThat(RapCallbackPolicy.DEFAULT.allows(java.net.URI.create("http://[::1]:9/cb"))).isTrue();
        RapCallbackPolicy allowlisted = RapCallbackPolicy.DEFAULT.allowingHosts("10.0.0.7");
        assertThat(allowlisted.allows(java.net.URI.create("http://10.0.0.7/cb"))).isTrue();
        assertThat(allowlisted.allows(java.net.URI.create("http://10.0.0.8/cb"))).isFalse();
    }

    @Test
    void callbacksAreRetriedOnServerErrorsButNotOnClientErrors() throws Exception {
        runtime.statuses.add(503);
        runtime.statuses.add(500);
        assertThat(invoke("local_echo", "c1", Map.of("text", "eventually")).get("text")).isEqualTo("eventually");
        assertThat(runtime.requests.get()).isEqualTo(3);
        runtime.requests.set(0);
        runtime.statuses.add(410);
        assertThat(post("/invoke", runtime.invocation("local_echo", "c2", Map.of("text", "x"))).status()).isEqualTo(200);
        assertThat(runtime.nextWithin(500)).isNull();
        assertThat(runtime.requests.get()).isEqualTo(1);
    }

    @Test
    void subscriptionEventsFollowTheResultInOrder() throws Exception {
        Map<String, Object> result = invoke("local_watch", "sub-1", Map.of());
        assertThat(result).containsEntry("text", "subscribed").containsEntry("subscription", true);
        assertThat(runtime.next()).isEqualTo(Map.of("type", "subscription_event", "group_id", "thread-1",
                "tool_call_id", "sub-1", "text", "first", "associative", false));
        assertThat(store.subscriptions()).singleElement().satisfies(s -> {
            assertThat(s.toolCallId()).isEqualTo("sub-1");
            assertThat(s.callbackUrl()).isEqualTo(runtime.callbackUrl());
            assertThat(s.caller()).isEqualTo("runtime-a");
        });
        RapSubscription sub = testTools.subscriptions.poll(5, TimeUnit.SECONDS);
        assertThat(sub.emit("second", true)).isTrue();
        assertThat(sub.close("bye", true)).isTrue();
        assertThat(runtime.next()).containsEntry("text", "second").containsEntry("associative", true)
                .doesNotContainKey("final");
        assertThat(runtime.next()).containsEntry("text", "bye").containsEntry("final", true);
        assertThat(sub.isActive()).isFalse();
        assertThat(sub.emit("after final")).isFalse();
        assertThat(store.subscriptions()).isEmpty();
    }

    @Test
    void cancelStopsASubscriptionAndARunningCall() throws Exception {
        invoke("local_watch", "sub-2", Map.of());
        runtime.next();
        RapSubscription sub = testTools.subscriptions.poll(5, TimeUnit.SECONDS);
        assertThat(post("/cancel_tool_call", Map.of("thread_id", "thread-1", "tool_call_id", "sub-2")).status())
                .isEqualTo(200);
        assertThat(testTools.cancelled.poll(5, TimeUnit.SECONDS)).isEqualTo("sub-2");
        assertThat(sub.emit("dropped")).isFalse();
        assertThat(runtime.nextWithin(300)).isNull();
        assertThat(store.subscriptions()).isEmpty();

        assertThat(post("/invoke", runtime.invocation("local_slow", "slow-1", Map.of())).status()).isEqualTo(200);
        Thread.sleep(200);
        post("/cancel_tool_call", Map.of("thread_id", "thread-1", "tool_call_id", "slow-1"));
        assertThat(runtime.next()).containsEntry("id", "slow-1").containsEntry("text", "stopped");

        assertThat(post("/cancel_tool_call", Map.of("thread_id", "t", "tool_call_id", "unknown")).status()).isEqualTo(200);
        assertThat(post("/cancel_tool_call", Map.of("thread_id", "t")).status()).isEqualTo(400);
    }

    @Test
    void closingAThreadEndsItsSubscriptionsWithAFinalEvent() throws Exception {
        invoke("local_watch", "sub-3", Map.of());
        runtime.next();
        assertThat(post("/close_thread", Map.of("thread_id", "thread-1")).status()).isEqualTo(200);
        assertThat(runtime.next()).containsEntry("tool_call_id", "sub-3").containsEntry("text", "")
                .containsEntry("final", true);
        assertThat(testTools.cancelled.poll(5, TimeUnit.SECONDS)).isEqualTo("sub-3");
        assertThat(post("/close_thread", Map.of("thread_id", "unknown")).status()).isEqualTo(200);
    }

    @Test
    void lifecycleRoutesRequireTheSameAuthenticationAsInvocations() {
        for (String path : List.of("/invoke", "/close_thread", "/cancel_tool_call", "/user_choice_response")) {
            assertThat(FakeRuntime.send("POST", server.baseUrl() + path, Map.of("thread_id", "t"), null).status())
                    .as(path).isEqualTo(401);
            assertThat(FakeRuntime.send("POST", server.baseUrl() + path, Map.of("thread_id", "t"), "bad").status())
                    .as(path).isEqualTo(401);
        }
    }

    @Test
    void userChoiceRoundTripTimeoutAndInvalidSelection() throws Exception {
        assertThat(post("/invoke", runtime.invocation("local_ask", "ask-1", Map.of())).status()).isEqualTo(200);
        Map<String, Object> prompt = runtime.next();
        assertThat(prompt).containsEntry("type", "user_choice").containsEntry("id", "ask-1")
                .containsEntry("prompt", "Proceed?").containsEntry("choices", List.of("yes", "no"))
                .containsEntry("default", 1).containsEntry("response_url", server.baseUrl() + "/user_choice_response");
        assertThat(post("/user_choice_response", Map.of("id", "ask-1", "selected", 0)).status()).isEqualTo(200);
        assertThat(runtime.next()).containsEntry("text", "chose 0");

        assertThat(post("/invoke", runtime.invocation("local_ask", "ask-2", Map.of())).status()).isEqualTo(200);
        runtime.next();
        post("/user_choice_response", Map.of("id", "ask-2", "selected", 5));
        assertThat(runtime.next().get("text")).asString().startsWith("Error: user choice flow failed");
        assertThat(post("/user_choice_response", Map.of("id", "x", "selected", -1)).status()).isEqualTo(400);
    }

    @Test
    void unansweredChoiceFallsBackToTheDefault() throws Exception {
        try (RapToolServer quick = RapToolServer.builder(LocalTools.builder().scan(new TestTools()).build())
                .choiceTimeout(Duration.ofMillis(200)).build();
             ProtoweftServer host = ProtoweftServer.builder().port(0).endpoints(quick).start()) {
            assertThat(FakeRuntime.send("POST", host.baseUrl() + "/invoke", runtime.invocation("ask", "a", Map.of()), null)
                    .status()).isEqualTo(200);
            assertThat(runtime.next()).containsEntry("type", "user_choice");
            assertThat(runtime.next()).containsEntry("text", "chose 1");
        }
    }

    @Test
    void authorizationRequestPrecedesTheResultAndCallerReachesTheTool() throws Exception {
        assertThat(post("/invoke", runtime.invocation("local_login", "l1", Map.of())).status()).isEqualTo(200);
        assertThat(runtime.next()).isEqualTo(Map.of("type", "oauth", "group_id", "thread-1", "id", "l1",
                "auth_url", "https://auth.example/authorize?state=s"));
        assertThat(runtime.next()).containsEntry("text", "authorized");
        Map<String, Object> inv = runtime.invocation("local_whoami", "w1", Map.of());
        inv.put("user_id", "user_42");
        post("/invoke", inv);
        assertThat(runtime.next()).containsEntry("text", "runtime-a/user_42/user_42");
    }

    @Test
    void contextIsUnavailableOverOtherProtocols() {
        LocalTools tools = LocalTools.builder().scan(new TestTools()).build();
        assertThat(tools.invoke(ToolInvocation.of("probe", Map.of())).text()).isEqualTo("other");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("login", Map.of())))
                .isInstanceOf(io.github.rahulbsw.protoweft.core.ProtoweftException.class)
                .hasMessageContaining("RapCallContext is not available");
    }

    @Test
    void serverComplianceUsesTheSharedFeatureIds() {
        assertThat(rap.compliance().protocol()).isEqualTo(ProtocolId.RAP);
        assertThat(rap.compliance().supports(RapFeatures.SUBSCRIPTION_EVENTS)).isTrue();
        assertThat(rap.compliance().unsupported()).contains(RapFeatures.VIEW_UPDATE, RapFeatures.MIGRATION);
    }
}
