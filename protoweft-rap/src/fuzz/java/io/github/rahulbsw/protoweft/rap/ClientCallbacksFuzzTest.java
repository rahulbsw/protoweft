package io.github.rahulbsw.protoweft.rap;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.github.rahulbsw.protoweft.rap.FuzzJson.obj;

/**
 * Fuzzes the callback endpoint's request handling: each document of the input (a line, or an
 * edited template, see {@link FuzzJson}) is one callback body POSTed in order for the same pending
 * call, so a {@code tool_result} that opens a subscription can be followed by
 * {@code subscription_event}s. A body may only ever produce a status (200, 400, 404 or 413); no
 * exception may escape, whatever the bytes. Prompts run inline, so {@code oauth} and
 * {@code user_choice} reach the handler. Seeds use {@code thread-1} / {@code call-1}.
 *
 * <p>Regression mode (the default) runs the seeds under {@code ClientCallbacksFuzzTestInputs/};
 * fuzz with {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class ClientCallbacksFuzzTest {

    private static final String TOKEN = "fuzz-token-0123456789abcdefghijklmnop";
    private static final Set<Integer> STATUSES = Set.of(200, 400, 404, 413);
    /** Keys and values that structured mode (see {@link FuzzJson}) builds messages from. */
    private static final List<String> WORDS = List.of("type", "group_id", "id", "tool_call_id", "call_id", "text",
            "content", "subscription", "associative", "final", "auth_url", "prompt", "choices", "default",
            "response_url", "view_type", "mediaType", "data", "tool_result", "subscription_event", "oauth",
            "user_choice", "view_update", "image", "thread-1", "call-1", "Error: x", "", "https://a.example/cb",
            "http://127.0.0.1:1/x", "HTTPS://A", "javascript:x");
    /** Valid callbacks for the pending call that structured mode edits. */
    private static final List<Object> TEMPLATES = List.of(
            obj("type", "tool_result", "group_id", "thread-1", "id", "call-1", "call_id", "c", "text", "hello"),
            obj("type", "tool_result", "group_id", "thread-1", "id", "call-1", "content", List.of(
                    obj("type", "text", "text", "a"), obj("type", "image", "mediaType", "image/png", "data", "AA=="),
                    obj("type", "audio"))),
            obj("type", "tool_result", "group_id", "thread-1", "id", "call-1", "text", "started", "subscription", true),
            obj("type", "subscription_event", "group_id", "thread-1", "tool_call_id", "call-1", "text", "tick",
                    "associative", true, "final", false),
            obj("type", "oauth", "group_id", "thread-1", "id", "call-1", "auth_url", "https://auth.example.com/a"),
            obj("type", "user_choice", "group_id", "thread-1", "id", "call-1", "prompt", "Pick", "choices",
                    List.of("a", "b"), "default", 1, "response_url", "https://runtime.example.com/choice/1"),
            obj("type", "view_update", "group_id", "thread-1", "view_type", "thread", "content", obj()));

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void callbackBodies(byte[] input) throws IOException {
        CallbackRouter router = new CallbackRouter(() -> "http://127.0.0.1:1");
        ClientCallbacks callbacks = new ClientCallbacks("fuzz", new InMemoryRapStore(), router, new SyncHooks(),
                Clock.systemUTC(), Duration.ofMinutes(5));
        CompletableFuture<ToolOutcome> future = new CompletableFuture<>();
        callbacks.open(new RapCall("echo", "call-1", "thread-1", "http://127.0.0.1:1"), TOKEN, future);

        for (byte[] body : FuzzJson.documents(input, WORDS, TEMPLATES)) {
            byte[] limited = CallbackServlet.readLimited(new ByteArrayInputStream(body));
            int status = limited == null ? 413 : router.dispatch(CallbackRouter.PATH + TOKEN, limited);
            if (!STATUSES.contains(status)) {
                throw new IllegalStateException("callback answered HTTP " + status);
            }
        }
        if (future.isDone() && !future.isCompletedExceptionally()) {
            ToolOutcome outcome = future.join();
            outcome.text(); // every part is well-formed
        }
        callbacks.shutdown(new IllegalStateException("fuzz input done"));
    }

    /** Prompts run inline; the handler checks what reaches it and answers out of range. */
    private static final class SyncHooks implements ClientCallbacks.Hooks {
        private final RapPromptHandler handler = new RapPromptHandler() {
            @Override
            public void onAuthorization(String authUrl, RapCall call) {
                if (authUrl == null) {
                    throw new IllegalStateException("oauth prompt without a URL");
                }
            }

            @Override
            public int onChoice(String prompt, List<String> choices, int defaultIndex, RapCall call) {
                if (prompt == null || choices.isEmpty() || defaultIndex < 0 || defaultIndex >= choices.size()) {
                    throw new IllegalStateException("invalid user_choice reached the prompt handler");
                }
                return choices.size(); // out of range: the connector must fall back to the default
            }
        };

        @Override
        public void prompt(Runnable task) {
            task.run();
        }

        @Override
        public RapPromptHandler prompts() {
            return handler;
        }

        @Override
        public void answerChoice(RapCall call, String responseUrl, int selected) {
            if (responseUrl == null || selected < 0) {
                throw new IllegalStateException("invalid choice answer: " + responseUrl + " " + selected);
            }
        }

        @Override
        public void recovered(RapRecoveredResult result) {
        }

        @Override
        public void cancelRemote(RapCall call) {
        }
    }
}
