package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;

import java.util.List;
import java.util.Map;

/** Local tools served by the MCP server tests, over HTTP and stdio alike. */
final class TestTools {

    /** A 1x1 transparent PNG. */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    private TestTools() {
    }

    static LocalTools create() {
        return LocalTools.builder()
                .tool("echo", "Echoes the message", schema(Map.of("message", Map.of("type", "string"))),
                        (args, caller) -> ToolOutcome.text(String.valueOf(args.get("message"))))
                .tool("add", "Adds two integers",
                        schema(Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer"))),
                        (args, caller) -> {
                            long sum = ((Number) args.get("a")).longValue() + ((Number) args.get("b")).longValue();
                            return new ToolOutcome(List.of(new Content.Text(Long.toString(sum))), false, Map.of("sum", sum));
                        })
                .tool("fail", "Always reports a tool error", schema(Map.of()),
                        (args, caller) -> ToolOutcome.failure("it broke"))
                .tool("whoami", "Returns the caller's name", schema(Map.of()),
                        (args, caller) -> ToolOutcome.text(caller.name()))
                .tool("strict", "Rejects its arguments", schema(Map.of()), (args, caller) -> {
                    throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, null, "date must be ISO-8601");
                })
                .tool("unavailable", "Its backend is down", schema(Map.of()), (args, caller) -> {
                    throw new AgentWeaveException(ErrorCategory.CONNECTION, null, "backend unreachable");
                })
                .tool("media", "Returns an image, JSON and a link", schema(Map.of()), (args, caller) -> new ToolOutcome(List.of(
                        new Content.Data("image/png", PNG),
                        new Content.Json(Map.of("k", "v")),
                        new Content.Link("https://example.com/report.pdf", "application/pdf", "report")), false, null))
                .tool("pdf", "Returns inline PDF bytes", schema(Map.of()),
                        (args, caller) -> new ToolOutcome(List.of(new Content.Data("application/pdf", "JVBERg==")), false, null))
                .tool("list", "Returns a JSON array as structured output", schema(Map.of()),
                        (args, caller) -> new ToolOutcome(List.of(new Content.Text("[1,2]")), false, List.of(1, 2)))
                .tool("slow", "Sleeps for 'ms' milliseconds", schema(Map.of("ms", Map.of("type", "integer"))),
                        (args, caller) -> {
                            Thread.sleep(((Number) args.get("ms")).longValue());
                            return ToolOutcome.text("done");
                        })
                .tool("noisy", "Prints to System.out", schema(Map.of()), (args, caller) -> {
                    System.out.println("this must not reach the protocol stream");
                    return ToolOutcome.text("quiet");
                })
                .build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }
}
