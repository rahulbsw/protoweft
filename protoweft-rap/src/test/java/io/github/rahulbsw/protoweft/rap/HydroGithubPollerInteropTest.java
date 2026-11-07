package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction A: our client against Hydro's GitHub event poller ({@code crates/rap-github-event-poller},
 * Infinity e1a3e4b), a subscription-only tool. Its events depend on live GitHub activity (it polls
 * the public Events API every 60 s and skips events that predate the subscription), so this test
 * checks the subscription confirmation and cancellation only. Skipped unless
 * {@code RAP_INTEROP_GITHUB_POLLER_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_GITHUB_POLLER_URL", matches = "https?://.+")
class HydroGithubPollerInteropTest {

    private static final String SERVER = "rap-github-event-poller";

    @Test
    void subscriptionConfirmationAndCancel() throws Exception {
        try (HydroInterop.Client c = new HydroInterop.Client(HydroInterop.env("RAP_INTEROP_GITHUB_POLLER_URL"),
                b -> b.timeout(Duration.ofSeconds(30)))) {
            ToolOutcome out = c.rap.invoke(ToolInvocation.of("subscribe_github_events",
                    Map.of("owner", "hydro-project", "repo", "infinity", "event_type", "PushEvent")));
            assertThat(out.error()).as(out.text()).isFalse();
            assertThat(out.text()).startsWith("Subscribed to events for hydro-project/infinity.");
            ToolSubscription sub = out.subscription().orElseThrow();
            assertThat(sub.isActive()).isTrue();
            HydroInterop.Events events = new HydroInterop.Events();
            sub.events().subscribe(events);
            sub.cancel();
            assertThat(events.awaitDone(Duration.ofSeconds(5))).isTrue();
            assertThat(sub.isActive()).isFalse();
            HydroInterop.report("A", SERVER, "tool_result/subscription:true", out.text());
            HydroInterop.report("A", SERVER, "cancel_tool_call(subscription)", "ToolSubscription.cancel() -> POST"
                    + " /cancel_tool_call (their handler removes the subscription); local stream completed");
            c.rap.closeThread(c.rap.threadId()).get(10, TimeUnit.SECONDS);
            HydroInterop.report("A", SERVER, "close_thread", "server has no /close_thread route (404); our"
                    + " notification completed without error");
        }
    }
}
