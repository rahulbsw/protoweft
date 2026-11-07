package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction A: our client against Hydro's JavaScript get-time tool server
 * ({@code agent/lib/toolsets/get-time/get-time-tool/local.mjs} at Infinity e1a3e4b), started by
 * {@code scripts/interop/rap.sh}. Skipped unless {@code RAP_INTEROP_GET_TIME_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_GET_TIME_URL", matches = "https?://.+")
class HydroGetTimeInteropTest {

    private static final String SERVER = "get-time(js)";

    @Test
    void discoveryInvocationAndTokenizedCallback() throws Exception {
        try (HydroInterop.Client c = new HydroInterop.Client(HydroInterop.env("RAP_INTEROP_GET_TIME_URL"),
                b -> b.timeout(Duration.ofSeconds(30)))) {
            List<ToolSpec> tools = c.rap.listTools();
            assertThat(tools).extracting(ToolSpec::name).containsExactly("get_time");
            ToolSpec spec = tools.get(0);
            assertThat(spec.description()).isEqualTo("Get the current time in a specified timezone or UTC.");
            assertThat(spec.inputSchema()).containsEntry("type", "object");
            assertThat(((Map<?, ?>) spec.inputSchema().get("properties")).keySet()).extracting(Object::toString).contains("timezone");
            assertThat(c.rap.rejectedTools()).isEmpty();
            HydroInterop.report("A", SERVER, "toolset/discovery", "tool get_time, inputSchema.properties="
                    + ((Map<?, ?>) spec.inputSchema().get("properties")).keySet());

            ToolOutcome utc = c.rap.invoke(ToolInvocation.of("get_time", Map.of()));
            assertThat(utc.error()).isFalse();
            assertThat(utc.text()).startsWith("Current UTC time: ");
            Instant reported = Instant.parse(utc.text().substring("Current UTC time: ".length()));
            assertThat(Duration.between(reported, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));

            ToolOutcome ny = c.rap.invoke(ToolInvocation.of("get_time", Map.of("timezone", "America/New_York")));
            assertThat(ny.error()).isFalse();
            assertThat(ny.text()).startsWith("Current time in America/New_York: ");

            ToolOutcome bad = c.rap.invoke(ToolInvocation.of("get_time", Map.of("timezone", "Not/AZone")));
            assertThat(bad.error()).isTrue();
            assertThat(bad.text()).startsWith("Error: ");

            assertThat(c.tap.settle().seen).hasSize(3).allSatisfy(s -> {
                assertThat(s.type()).isEqualTo("tool_result");
                assertThat(s.status()).isEqualTo(200);
                assertThat(s.path()).matches("/rap/callback/[A-Za-z0-9_-]{32,128}");
                assertThat(s.body()).containsKey("call_id");
            });
            HydroInterop.report("A", SERVER, "tool_result/text", "3 tool_result callbacks POSTed to our tokenized URL "
                    + c.tap.seen.get(0).path().replaceAll("[A-Za-z0-9_-]{32,}", "<token>") + ", answered "
                    + c.tap.seen.stream().map(HydroInterop.CallbackTap.Seen::status).toList() + "; texts: "
                    + List.of(utc.text(), ny.text()));
            HydroInterop.report("A", SERVER, "tool_result/error", "invalid timezone -> error outcome " + bad.text());

            // local.mjs routes every POST to the tool (no /close_thread route): it answers 200 and
            // fails to post a result for the missing callback_url. Our client only needs it not to hang.
            c.rap.closeThread(c.rap.threadId()).get(10, TimeUnit.SECONDS);
            HydroInterop.report("A", SERVER, "close_thread", "POST /close_thread completed (local.mjs answers any POST with 200)");
        }
    }
}
