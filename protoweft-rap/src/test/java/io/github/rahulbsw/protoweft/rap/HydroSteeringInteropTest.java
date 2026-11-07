package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction A: our client against Hydro's steering-file server ({@code crates/rap-steering-server},
 * Infinity e1a3e4b). Skipped unless {@code RAP_INTEROP_STEERING_URL} and
 * {@code RAP_INTEROP_STEERING_ROOT} (a directory holding an {@code AGENTS.md}) are set.
 */
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_STEERING_URL", matches = "https?://.+")
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_STEERING_ROOT", matches = ".+")
class HydroSteeringInteropTest {

    private static final String SERVER = "rap-steering-server";

    @Test
    void listAndLoadSteeringFiles() throws Exception {
        String root = HydroInterop.env("RAP_INTEROP_STEERING_ROOT");
        try (HydroInterop.Client c = new HydroInterop.Client(HydroInterop.env("RAP_INTEROP_STEERING_URL"),
                b -> b.timeout(Duration.ofSeconds(30)))) {
            assertThat(c.rap.listTools()).extracting(ToolSpec::name).containsExactlyInAnyOrder("list_steering", "load_steering");
            ToolOutcome list = c.rap.invoke(ToolInvocation.of("list_steering", Map.of("root", root)));
            assertThat(list.error()).as(list.text()).isFalse();
            assertThat(list.text()).contains("AGENTS.md");
            ToolOutcome load = c.rap.invoke(ToolInvocation.of("load_steering", Map.of("root", root, "path", "AGENTS.md")));
            assertThat(load.error()).as(load.text()).isFalse();
            assertThat(load.text()).contains("Protoweft interop steering file");
            ToolOutcome bad = c.rap.invoke(ToolInvocation.of("load_steering", Map.of("root", root, "path", "../etc/passwd")));
            assertThat(bad.error()).isTrue();
            assertThat(c.tap.settle().types()).containsOnly("tool_result");
            HydroInterop.report("A", SERVER, "tool_result/text", "list_steering lists AGENTS.md; load_steering returns"
                    + " its content; rejected path -> " + bad.text());
            // The steering server has no /close_thread route (axum answers 404); the spec says
            // servers always answer 200 (thread-closure.md:35). Our notification still completes.
            c.rap.closeThread(c.rap.threadId()).get(10, TimeUnit.SECONDS);
            HydroInterop.report("A", SERVER, "close_thread", "server has no /close_thread route (404); our"
                    + " best-effort notification completed without error");
        }
    }
}
