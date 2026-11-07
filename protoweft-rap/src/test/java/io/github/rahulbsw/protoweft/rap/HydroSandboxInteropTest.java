package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction A: our client against Hydro's sandbox tool server ({@code crates/sandbox-local}
 * binary over {@code crates/sandbox-core}'s router, Infinity e1a3e4b) on a scratch git repository
 * prepared by {@code scripts/interop/rap.sh}. It is the reference server that uses every RAP
 * route: results with {@code display_as}, {@code content} images, {@code view_update},
 * subscriptions with streamed events and {@code final}, {@code user_choice} answered at
 * {@code /user_choice_response}, {@code /cancel_tool_call} and {@code /close_thread}.
 *
 * <p>All calls share one thread ({@code group_id}) because the sandbox is per thread. Skipped
 * unless {@code RAP_INTEROP_SANDBOX_URL} and {@code RAP_INTEROP_SANDBOX_REPO} are set;
 * {@code RAP_INTEROP_SANDBOX_LOG} (their {@code rap-sandbox.log}) adds server-side evidence.
 */
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_SANDBOX_URL", matches = "https?://.+")
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_SANDBOX_REPO", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HydroSandboxInteropTest {

    private static final String SERVER = "sandbox-local";

    /** Answers prompts with {@link #answer}; -1 dismisses (our client then sends the default). */
    private volatile int answer = -1;
    private final BlockingQueue<String> prompts = new LinkedBlockingQueue<>();
    private HydroInterop.Client c;
    private Path repo;

    @BeforeAll
    void connect() throws Exception {
        repo = Path.of(HydroInterop.env("RAP_INTEROP_SANDBOX_REPO")).toRealPath();
        RapPromptHandler handler = new RapPromptHandler() {
            @Override
            public void onAuthorization(String authUrl, RapCall call) {
                prompts.add("auth:" + authUrl);
            }

            @Override
            public int onChoice(String prompt, List<String> choices, int defaultIndex, RapCall call) {
                prompts.add(prompt + " " + choices + " default=" + defaultIndex);
                return answer;
            }
        };
        c = new HydroInterop.Client(HydroInterop.env("RAP_INTEROP_SANDBOX_URL"), b -> b
                .threadId("pw-interop-" + UUID.randomUUID()).promptHandler(handler).timeout(Duration.ofSeconds(90)));
    }

    @AfterAll
    void disconnect() {
        if (c != null) {
            c.close();
        }
    }

    private ToolOutcome call(String tool, Map<String, Object> args) {
        return c.rap.invoke(ToolInvocation.of(tool, args));
    }

    private long logCount(String needle) throws Exception {
        String log = System.getenv("RAP_INTEROP_SANDBOX_LOG");
        if (log == null || !Files.exists(Path.of(log))) {
            return -1;
        }
        return Files.readAllLines(Path.of(log)).stream().filter(l -> l.contains(needle)).count();
    }

    @Test
    @Order(1)
    void discoveryAndCloneRepo() {
        assertThat(c.rap.listTools()).extracting(ToolSpec::name).contains("clone_repo", "execute_command",
                "read_file", "edit_file", "create_file", "grep", "describe_overall_changes", "squash_sandbox",
                "open_sandbox_direct");
        assertThat(c.rap.rejectedTools()).isEmpty();
        ToolOutcome cloned = call("clone_repo", Map.of("repo", repo.toString()));
        assertThat(cloned.error()).as(cloned.text()).isFalse();
        HydroInterop.report("A", SERVER, "toolset/discovery", c.rap.listTools().size()
                + " tools (displayScript ignored), endpoint /invoke");
        HydroInterop.report("A", SERVER, "tool_result/text", "clone_repo -> " + cloned.text());
    }

    @Test
    @Order(2)
    void commandResultWithinFiveSeconds() {
        ToolOutcome out = call("execute_command", Map.of("command", "echo hello-from-protoweft"));
        assertThat(out.error()).isFalse();
        assertThat(out.text()).contains("hello-from-protoweft").contains("[exit code: 0]");
        assertThat(out.subscription()).isEmpty();
        HydroInterop.report("A", SERVER, "tool_result/text", "execute_command (sandbox-exec) -> " + out.text());
    }

    @Test
    @Order(3)
    void readFileTextAndImageContent() throws Exception {
        ToolOutcome text = call("read_file", Map.of("path", "README.md"));
        assertThat(text.error()).isFalse();
        assertThat(text.text()).contains("hello interop");
        ToolOutcome image = call("read_file", Map.of("path", "pixel.png"));
        assertThat(image.error()).isFalse();
        String expected = Base64.getEncoder().encodeToString(Files.readAllBytes(repo.resolve("pixel.png")));
        assertThat(image.content()).hasSize(2);
        assertThat(image.content().get(0)).isInstanceOf(Content.Text.class);
        assertThat(image.content().get(1)).isEqualTo(new Content.Data("image/png", expected));
        HydroInterop.report("A", SERVER, "tool_result/content(image)", "read_file pixel.png -> ["
                + image.content().get(0) + ", Content.Data(image/png, " + expected.length() + " base64 chars)]"
                + "; display_as dropped");
    }

    @Test
    @Order(4)
    void editFileSendsViewUpdateAndDisplayAs() throws Exception {
        int before = c.tap.seen.size();
        ToolOutcome out = call("edit_file", Map.of("path", "README.md", "old_str", "line two",
                "new_str", "line two (edited by protoweft)"));
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(out.text()).isEqualTo("Replaced text in README.md");
        List<HydroInterop.CallbackTap.Seen> seen = c.tap.settle().since(before);
        assertThat(seen).extracting(HydroInterop.CallbackTap.Seen::type).containsExactly("view_update", "tool_result");
        assertThat(seen).allSatisfy(s -> assertThat(s.status()).isEqualTo(200));
        assertThat(seen.get(1).body()).containsKey("display_as");
        HydroInterop.report("A", SERVER, "view_update", "edit_file: view_update(view_type="
                + seen.get(0).body().get("view_type") + ") answered 200 and ignored, then tool_result with"
                + " display_as[diff] -> " + out.text());
    }

    @Test
    @Order(5)
    void userChoiceAnsweredAtResponseUrl() throws Exception {
        Path extra = Files.createDirectories(repo.getParent().resolve("extra-write"));
        answer = choiceIndex("Yes once");
        int before = c.tap.seen.size();
        ToolOutcome approved = call("execute_command", Map.of("command", "echo approved",
                "additional_permissions", List.of("write:" + extra)));
        String prompt = prompts.poll(5, TimeUnit.SECONDS);
        assertThat(prompt).startsWith("Allow writing to " + extra + "?").contains("Yes once", "No");
        assertThat(approved.error()).as(approved.text()).isFalse();
        assertThat(approved.text()).contains("approved");
        List<HydroInterop.CallbackTap.Seen> seen = c.tap.settle().since(before);
        assertThat(seen).extracting(HydroInterop.CallbackTap.Seen::type).containsExactly("user_choice", "tool_result");
        String responseUrl = (String) seen.get(0).body().get("response_url");
        assertThat(responseUrl).endsWith("/user_choice_response");
        HydroInterop.report("A", SERVER, "user_choice", "prompt \"" + prompt + "\"; handler chose \"Yes once\" -> "
                + "{id,selected} POSTed to " + responseUrl + "; command ran -> " + approved.text());

        answer = -1;
        ToolOutcome denied = call("execute_command", Map.of("command", "echo should-not-run",
                "additional_permissions", List.of("write:" + extra)));
        assertThat(prompts.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(denied.error()).isTrue();
        assertThat(denied.text()).isEqualTo("Error: write permission denied by user.");
        HydroInterop.report("A", SERVER, "user_choice(dismissed->default)", "handler dismissed; our client sent the"
                + " default index (\"No\") -> " + denied.text());
    }

    private static int choiceIndex(String label) {
        // sandbox-core builds [Yes (<thread> and all children), Yes once, No] for a root thread
        // (crates/sandbox-core/src/server.rs:496-505, 887-891).
        return label.equals("Yes once") ? 1 : 2;
    }

    @Test
    @Order(6)
    void longCommandStreamsSubscriptionEventsAndFinal() throws Exception {
        ToolOutcome out = call("execute_command",
                Map.of("command", "echo start; sleep 6; echo middle; sleep 11; echo end"));
        assertThat(out.error()).isFalse();
        assertThat(out.text()).startsWith("Command is still running.").contains("start");
        ToolSubscription sub = out.subscription().orElseThrow();
        HydroInterop.Events events = new HydroInterop.Events();
        sub.events().subscribe(events);
        assertThat(events.awaitDone(Duration.ofSeconds(40))).as("final event").isTrue();
        assertThat(events.error).isNull();
        List<ToolEvent> got = events.events;
        assertThat(got).hasSizeGreaterThanOrEqualTo(2);
        assertThat(got.get(0).text()).contains("middle");
        assertThat(got.get(0).metadata()).containsEntry("associative", true).containsEntry("final", false);
        ToolEvent last = got.get(got.size() - 1);
        assertThat(last.last()).isTrue();
        assertThat(last.text()).contains("end").contains("[exit code: 0]");
        assertThat(sub.isActive()).isFalse();
        HydroInterop.report("A", SERVER, "tool_result/subscription:true", "execute_command still running after 5 s"
                + " -> subscription confirmed: " + out.text().lines().findFirst().orElse(""));
        HydroInterop.report("A", SERVER, "subscription_event(+final)", "events (text, metadata) = "
                + got.stream().map(e -> e.text() + " " + e.metadata()).toList());
    }

    @Test
    @Order(7)
    void cancellingASubscriptionKillsTheCommand() throws Exception {
        long signalsBefore = logCount("sent cancel signal to command handler");
        ToolOutcome out = call("execute_command", Map.of("command", "sleep 60"));
        ToolSubscription sub = out.subscription().orElseThrow();
        HydroInterop.Events events = new HydroInterop.Events();
        sub.events().subscribe(events);
        int before = c.tap.settle().seen.size();
        sub.cancel();
        assertThat(events.awaitDone(Duration.ofSeconds(5))).isTrue();
        assertThat(sub.isActive()).isFalse();
        // sandbox-core answers a cancel during streaming with a view_update (if files changed) and
        // one more final event ("[cancelled]", server.rs:1170-1201), although
        // subscription-events.md:173 says no events follow a cancellation. Our client must accept
        // it (200, the token is remembered as finished) and not deliver it.
        assertThat(c.tap.awaitType(before, "subscription_event", Duration.ofSeconds(10))).isTrue();
        List<HydroInterop.CallbackTap.Seen> late = c.tap.settle().since(before);
        assertThat(late).allSatisfy(s -> {
            assertThat(s.type()).isIn("subscription_event", "view_update");
            assertThat(s.status()).isEqualTo(200);
        });
        HydroInterop.CallbackTap.Seen fin = late.stream().filter(s -> s.type().equals("subscription_event"))
                .findFirst().orElseThrow();
        assertThat(fin.body()).containsEntry("final", true);
        assertThat(events.events).isEmpty();
        long signalsAfter = logCount("sent cancel signal to command handler");
        if (signalsBefore >= 0) {
            assertThat(signalsAfter).isEqualTo(signalsBefore + 1);
        }
        HydroInterop.report("A", SERVER, "cancel_tool_call(subscription)", "ToolSubscription.cancel() -> POST"
                + " /cancel_tool_call; server log 'sent cancel signal to command handler' "
                + (signalsBefore >= 0 ? signalsBefore + "->" + signalsAfter : "(log not available)")
                + "; their post-cancel callbacks " + late + " (event text " + fin.body().get("text")
                + ", final=true) answered 200 and not delivered (0 events)");
    }

    @Test
    @Order(8)
    void cancellingAPendingCallBeforeItsResult() throws Exception {
        Thread.sleep(1000);
        long signalsBefore = logCount("sent cancel signal to command handler");
        int before = c.tap.settle().seen.size();
        CompletableFuture<ToolOutcome> future = c.rap.invokeAsync(ToolInvocation.of("execute_command",
                Map.of("command", "sleep 30")));
        Thread.sleep(1500);
        future.cancel(true);
        Thread.sleep(3000);
        long signalsAfter = logCount("sent cancel signal to command handler");
        if (signalsBefore >= 0) {
            assertThat(signalsAfter).isEqualTo(signalsBefore + 1);
        }
        assertThat(c.tap.since(before)).isEmpty();
        HydroInterop.report("A", SERVER, "cancel_tool_call(pending call)", "invokeAsync future cancelled after 1.5 s"
                + " -> POST /cancel_tool_call; server log cancel signals "
                + (signalsBefore >= 0 ? signalsBefore + "->" + signalsAfter : "(log not available)")
                + "; no callback followed in 3 s");
    }

    @Test
    @Order(9)
    void closeThread() throws Exception {
        long before = logCount("received close_thread notification");
        c.rap.closeThread(c.rap.threadId()).get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long after = logCount("received close_thread notification");
        while (before >= 0 && after == before && System.nanoTime() < deadline) {
            Thread.sleep(100);
            after = logCount("received close_thread notification");
        }
        if (before >= 0) {
            assertThat(after).isEqualTo(before + 1);
        }
        HydroInterop.report("A", SERVER, "close_thread", "POST /close_thread {thread_id} -> 200; server log"
                + " 'received close_thread notification' " + (before >= 0 ? before + "->" + after : "(log not available)"));
    }
}
