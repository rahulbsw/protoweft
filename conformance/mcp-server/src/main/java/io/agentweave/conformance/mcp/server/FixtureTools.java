package io.agentweave.conformance.mcp.server;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolProgress;

import java.util.List;
import java.util.Map;

/**
 * The tools of the MCP conformance suite's server scenarios ({@code src/scenarios/server/tools.ts}
 * in {@code @modelcontextprotocol/conformance}), written with AgentWeave's public API only.
 *
 * <p>Not published here, because AgentWeave does not implement them (see the baseline):
 * {@code test_sampling} (sampling), {@code test_elicitation},
 * {@code test_elicitation_sep1034_defaults} and {@code test_elicitation_sep1330_enums}
 * (elicitation). A server cannot send {@code sampling/createMessage} or
 * {@code elicitation/create} through {@code ToolProgress}, so these are not faked.
 */
final class FixtureTools {

    /** A 1x1 red PNG, as the suite's reference servers use. */
    static final String RED_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    /** A minimal WAV file. */
    static final String MINIMAL_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";

    private static final Map<String, Object> NO_ARGS = Map.of("type", "object", "properties", Map.of());

    private FixtureTools() {
    }

    static LocalTools create() {
        return LocalTools.builder()
                .tool("test_simple_text", "Returns simple text content for testing", NO_ARGS,
                        (args, caller) -> ToolOutcome.text("This is a simple text response for testing."))
                .tool("test_image_content", "Returns image content for testing", NO_ARGS,
                        (args, caller) -> outcome(new Content.Data("image/png", RED_PIXEL_PNG)))
                .tool("test_audio_content", "Returns audio content for testing", NO_ARGS,
                        (args, caller) -> outcome(new Content.Data("audio/wav", MINIMAL_WAV)))
                .tool("test_embedded_resource", "Returns embedded resource content for testing", NO_ARGS,
                        (args, caller) -> outcome(
                                Content.Resource.text("test://embedded-resource", "text/plain",
                                        "This is an embedded resource content.")))
                .tool("test_multiple_content_types", "Returns multiple content types for testing", NO_ARGS,
                        (args, caller) -> outcome(
                                new Content.Text("Multiple content types test:"),
                                new Content.Data("image/png", RED_PIXEL_PNG),
                                Content.Resource.text("test://mixed-content-resource", "application/json",
                                        "{\"test\":\"data\",\"value\":123}")))
                .tool("test_tool_with_logging", "Tool that sends log messages during execution", NO_ARGS,
                        FixtureTools::withLogging)
                .tool("test_error_handling", "Tool that returns an error for testing error handling", NO_ARGS,
                        (args, caller) -> ToolOutcome.failure("This tool intentionally returns an error for testing"))
                .tool("test_tool_with_progress", "Tool that reports progress notifications", NO_ARGS,
                        FixtureTools::withProgress)
                .build();
    }

    /** Three info messages, about 50 ms apart, as the scenario describes. */
    private static ToolOutcome withLogging(Map<String, Object> args, CallerIdentity caller, ToolProgress progress)
            throws InterruptedException {
        progress.info("Tool execution started");
        Thread.sleep(50);
        progress.info("Tool processing data");
        Thread.sleep(50);
        progress.info("Tool execution completed");
        return ToolOutcome.text("Tool execution completed with logging");
    }

    /** Progress 0, 50 and 100 of 100, about 50 ms apart; sent only when the request carries a token. */
    private static ToolOutcome withProgress(Map<String, Object> args, CallerIdentity caller, ToolProgress progress)
            throws InterruptedException {
        for (int done = 0; done <= 100; done += 50) {
            progress.progress(done, 100.0, null);
            if (done < 100) {
                Thread.sleep(50);
            }
        }
        return ToolOutcome.text("Tool execution completed with progress");
    }

    private static ToolOutcome outcome(Content... parts) {
        return new ToolOutcome(List.of(parts), false, null);
    }
}
