package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientPromptTest {

    private FakeToolServer fake;
    private RapCallbackListener listener;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeToolServer();
        listener = RapCallbackListener.builder().start();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        fake.close();
    }

    /** Records prompts; answers choices with a fixed index. */
    static final class RecordingHandler implements RapPromptHandler {
        final BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        final int answer;

        RecordingHandler(int answer) {
            this.answer = answer;
        }

        @Override
        public void onAuthorization(String authUrl, RapCall call) {
            seen.add("auth:" + authUrl + ":" + call.operation());
        }

        @Override
        public int onChoice(String prompt, List<String> choices, int defaultIndex, RapCall call) {
            seen.add("choice:" + prompt + ":" + choices + ":" + defaultIndex);
            return answer;
        }
    }

    private static Map<String, Object> oauth(Map<String, Object> inv, String url) {
        return Map.of("type", "oauth", "group_id", inv.get("group_id"), "id", inv.get("id"), "auth_url", url);
    }

    private static Map<String, Object> choice(Map<String, Object> inv, String responseUrl) {
        return Map.of("type", "user_choice", "group_id", inv.get("group_id"), "id", inv.get("id"),
                "prompt", "Allow writing?", "choices", List.of("Yes for session", "Yes once", "No"), "default", 2,
                "response_url", responseUrl);
    }

    @Test
    void oauthWithHandlerKeepsTheCallPendingUntilTheResult() throws Exception {
        RecordingHandler handler = new RecordingHandler(0);
        try (RapClientConnector c = RapClientConnector.builder(fake.baseUrl()).callbacks(listener)
                .promptHandler(handler).build()) {
            c.start();
            CompletableFuture<ToolOutcome> future = c.invokeAsync(ToolInvocation.of("echo", Map.of()));
            Map<String, Object> inv = fake.nextInvocation();
            String url = (String) inv.get("callback_url");
            assertThat(FakeToolServer.post(url, oauth(inv, "http://insecure.example/auth"))).isEqualTo(400);
            assertThat(FakeToolServer.post(url, oauth(inv, "https://github.com/login/oauth/authorize?x=1"))).isEqualTo(200);
            assertThat(handler.seen.poll(5, TimeUnit.SECONDS))
                    .isEqualTo("auth:https://github.com/login/oauth/authorize?x=1:echo");
            assertThat(future).isNotDone();
            FakeToolServer.post(url, FakeToolServer.result(inv, "authorized result"));
            assertThat(future.get(5, TimeUnit.SECONDS).text()).isEqualTo("authorized result");
        }
    }

    @Test
    void oauthWithoutHandlerFailsWithTheUrlAndALaterResultIsRecovered() throws Exception {
        BlockingQueue<RapRecoveredResult> recovered = new LinkedBlockingQueue<>();
        try (RapClientConnector c = RapClientConnector.builder(fake.baseUrl()).callbacks(listener).build()) {
            c.start();
            c.onRecoveredResult(recovered::add);
            CompletableFuture<ToolOutcome> future = c.invokeAsync(ToolInvocation.of("echo", Map.of()));
            Map<String, Object> inv = fake.nextInvocation();
            String url = (String) inv.get("callback_url");
            FakeToolServer.post(url, oauth(inv, "https://auth.example/authorize"));
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(RapAuthorizationRequiredException.class)
                    .satisfies(e -> {
                        RapAuthorizationRequiredException auth = (RapAuthorizationRequiredException) e;
                        assertThat(auth.category()).isEqualTo(ErrorCategory.AUTHENTICATION);
                        assertThat(auth.authUrl()).isEqualTo("https://auth.example/authorize");
                        assertThat(auth.call().toolCallId()).isEqualTo(inv.get("id"));
                    });
            FakeToolServer.post(url, FakeToolServer.result(inv, "done after authorization"));
            RapRecoveredResult r = recovered.poll(5, TimeUnit.SECONDS);
            assertThat(r).isNotNull();
            assertThat(r.outcome().text()).isEqualTo("done after authorization");
            assertThat(r.call().toolCallId()).isEqualTo(inv.get("id"));
        }
    }

    @Test
    void userChoiceIsAnsweredByTheHandler() throws Exception {
        RecordingHandler handler = new RecordingHandler(1);
        try (RapClientConnector c = RapClientConnector.builder(fake.baseUrl()).callbacks(listener)
                .promptHandler(handler).build()) {
            c.start();
            CompletableFuture<ToolOutcome> future = c.invokeAsync(ToolInvocation.of("echo", Map.of()));
            Map<String, Object> inv = fake.nextInvocation();
            String url = (String) inv.get("callback_url");
            assertThat(FakeToolServer.post(url, Map.of("type", "user_choice", "group_id", inv.get("group_id"),
                    "id", inv.get("id"), "prompt", "p", "choices", List.of("a"), "default", 3,
                    "response_url", fake.baseUrl() + "/user_choice_response"))).isEqualTo(400);
            assertThat(FakeToolServer.post(url, choice(inv, fake.baseUrl() + "/user_choice_response"))).isEqualTo(200);
            assertThat(FakeToolServer.poll(fake.choiceAnswers)).isEqualTo(Map.of("id", inv.get("id"), "selected", 1));
            assertThat(handler.seen.poll()).isEqualTo("choice:Allow writing?:[Yes for session, Yes once, No]:2");
            assertThat(future).isNotDone();
            FakeToolServer.post(url, FakeToolServer.result(inv, "wrote once"));
            assertThat(future.get(5, TimeUnit.SECONDS).text()).isEqualTo("wrote once");
        }
    }

    @Test
    void userChoiceWithoutHandlerSendsTheDefault() throws Exception {
        try (RapClientConnector c = RapClientConnector.builder(fake.baseUrl()).callbacks(listener).build()) {
            c.start();
            c.invokeAsync(ToolInvocation.of("echo", Map.of()));
            Map<String, Object> inv = fake.nextInvocation();
            FakeToolServer.post((String) inv.get("callback_url"), choice(inv, fake.baseUrl() + "/user_choice_response"));
            assertThat(FakeToolServer.poll(fake.choiceAnswers)).isEqualTo(Map.of("id", inv.get("id"), "selected", 2));
        }
    }

    @Test
    void outOfRangeOrFailingHandlerAnswersCountAsDismissal() throws Exception {
        try (RapClientConnector c = RapClientConnector.builder(fake.baseUrl()).callbacks(listener)
                .promptHandler(new RecordingHandler(7)).build()) {
            c.start();
            c.invokeAsync(ToolInvocation.of("echo", Map.of()));
            Map<String, Object> inv = fake.nextInvocation();
            FakeToolServer.post((String) inv.get("callback_url"), choice(inv, fake.baseUrl() + "/user_choice_response"));
            assertThat(FakeToolServer.poll(fake.choiceAnswers)).containsEntry("selected", 2);
        }
    }
}
