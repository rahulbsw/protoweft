package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction B: serves {@link HydroInteropTools} with {@link RapToolServer} on Jetty at
 * {@code 127.0.0.1:$RAP_INTEROP_SERVE_PORT} while Hydro's Rust runtime code
 * ({@code interop/rap/rust-client}, run by {@code scripts/interop/rap.sh}) drives it. Writes the
 * base URL to {@code $RAP_INTEROP_READY_FILE}, waits for {@code $RAP_INTEROP_STOP_FILE} (at most
 * {@code $RAP_INTEROP_SERVE_SECONDS}, default 300), then asserts what reached the tools: every
 * tool ran, {@code user_id} arrived, the choice answered at {@code response_url} reached
 * {@code askUser}, and Hydro's {@code /cancel_tool_call} and {@code /close_thread} ended the
 * subscriptions and the running call. Skipped unless {@code RAP_INTEROP_SERVE_PORT} is set.
 */
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_SERVE_PORT", matches = "\\d+")
class HydroRuntimeServeInteropTest {

    private static final String SERVER = "protoweft-rap-server";

    @Test
    void serveToHydroRuntime() throws Exception {
        int port = Integer.parseInt(HydroInterop.env("RAP_INTEROP_SERVE_PORT"));
        Path ready = Path.of(HydroInterop.env("RAP_INTEROP_READY_FILE"));
        Path stop = Path.of(HydroInterop.env("RAP_INTEROP_STOP_FILE"));
        Duration max = Duration.ofSeconds(Long.parseLong(HydroInterop.envOr("RAP_INTEROP_SERVE_SECONDS", "300")));
        HydroInteropTools tools = new HydroInteropTools();
        try (RapToolServer rap = RapToolServer.builder(LocalTools.builder().scan(tools).build())
                .name("protoweft-interop").description("Protoweft RAP server under Hydro's runtime").build();
             ProtoweftServer server = ProtoweftServer.builder().host("127.0.0.1").port(port).endpoints(rap).start()) {
            Files.deleteIfExists(stop);
            Files.writeString(ready, server.baseUrl());
            long deadline = System.nanoTime() + max.toNanos();
            while (!Files.exists(stop)) {
                assertThat(System.nanoTime()).as("the Rust harness did not finish within " + max).isLessThan(deadline);
                Thread.sleep(200);
            }
            Thread.sleep(500); // let in-flight background work (thread closure) settle
        } finally {
            tools.shutdown();
        }

        assertThat(tools.invoked).contains("echo", "image", "fail", "whoami", "ticker", "confirm", "login",
                "watch", "slow");
        assertThat(tools.userIds).containsExactly("alice");
        assertThat(tools.choices).containsExactly("yes");
        assertThat(tools.cancelledCalls).anySatisfy(c -> assertThat(c).startsWith("hydro-thread-2/"));
        assertThat(tools.ended).anySatisfy(c -> assertThat(c).startsWith("hydro-thread-2/"));
        assertThat(tools.ended).anySatisfy(c -> assertThat(c).startsWith("hydro-thread-3/"));
        HydroInterop.report("B", SERVER, "server-side observations", "tools invoked " + tools.invoked
                + "; user_id " + tools.userIds + "; askUser answers " + tools.choices
                + "; running calls cancelled " + tools.cancelledCalls + "; subscriptions ended " + tools.ended);
    }
}
