package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction A: our client against Hydro's Rust stub tool servers ({@code crates/rap-test-servers},
 * the servers their TUI/web e2e suites use), run by {@code interop/rap/rust-client}'s
 * {@code rap-stub-servers}. Both are configured on one connector ({@code addServer}). Skipped
 * unless {@code RAP_INTEROP_STUB_IMAGE_URL} and {@code RAP_INTEROP_STUB_DIFF_URL} are set.
 */
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_STUB_IMAGE_URL", matches = "https?://.+")
@EnabledIfEnvironmentVariable(named = "RAP_INTEROP_STUB_DIFF_URL", matches = "https?://.+")
class HydroStubServersInteropTest {

    /** rap-test-servers' STUB_PNG_BASE64 (crates/rap-test-servers/src/lib.rs:23). */
    private static final String STUB_PNG = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAIAAABqVuVZAAAAaUlEQVR42u3QQQkAAAgEsOsq2N8GNvDtY7AES/VwiAJBggQJEiRIkCAECRIkSJAgQYIQJEiQIEGCBAlCkCBBggQJEiRIEIIECRIkSJAgQQgSJEiQIEGCBCFIkCBBggQJEiQIQYIECfpnAbKIcmjVgp/0AAAAAElFTkSuQmCC";

    @Test
    void imageContentAndDiffDisplayResults() throws Exception {
        try (HydroInterop.Client c = new HydroInterop.Client(HydroInterop.env("RAP_INTEROP_STUB_IMAGE_URL"),
                b -> b.addServer(HydroInterop.env("RAP_INTEROP_STUB_DIFF_URL")).timeout(Duration.ofSeconds(30)))) {
            assertThat(c.rap.listTools()).extracting(ToolSpec::name).containsExactlyInAnyOrder("read_image", "edit_file");
            assertThat(c.rap.rejectedTools()).isEmpty();
            HydroInterop.report("A", "rap-test-servers", "toolset/discovery",
                    "two toolsets on one connector: stub-images(read_image) + stub-diffs(edit_file, with displayScript, ignored)");

            ToolOutcome image = c.rap.invoke(ToolInvocation.of("read_image", Map.of("path", "pixel.png")));
            assertThat(image.error()).isFalse();
            assertThat(image.content()).containsExactly(
                    new Content.Text("Read image file \"pixel.png\" (image/png). The image is attached."),
                    new Content.Data("image/png", STUB_PNG));
            assertThat(c.tap.settle().seen.get(0).body()).doesNotContainKey("text").containsKeys("content", "display_as");
            HydroInterop.report("A", "rap-test-servers", "tool_result/content(image)",
                    "content-only tool_result (no top-level text) -> [Content.Text, Content.Data(image/png, "
                            + STUB_PNG.length() + " base64 chars)]; display_as [image,text] dropped");

            ToolOutcome diff = c.rap.invoke(ToolInvocation.of("edit_file", Map.of("path", "src/main.rs")));
            assertThat(diff.error()).isFalse();
            assertThat(diff.text()).isEqualTo("Replaced text in src/main.rs.");
            assertThat((List<?>) c.tap.settle().seen.get(1).body().get("display_as")).hasSize(1);
            HydroInterop.report("A", "rap-test-servers", "tool_result/text+display_as(diff)",
                    "text " + diff.text() + "; display_as [diff] dropped; callbacks " + c.tap.seen);
        }
    }
}
