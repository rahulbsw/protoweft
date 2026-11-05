package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.observe.ObservationHandler.Observation;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.core.tool.ToolSpec;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Serves a {@link ToolSource} through the MCP SDK's server, independent of the transport.
 * Content and error mapping decisions are recorded in SPEC.md.
 */
final class ToolServerCore {

    /** Transport-context key under which the HTTP transport passes the authenticated caller. */
    static final String CALLER_KEY = "io.agentweave.caller";

    private static final Map<String, Object> EMPTY_OBJECT_SCHEMA = Map.of("type", "object");

    private final ToolSource tools;
    private final String serverName;
    private final String serverVersion;
    private final ObservationHandler observations;
    private final Duration requestTimeout;

    ToolServerCore(ToolSource tools, String serverName, String serverVersion,
                   ObservationHandler observations, Duration requestTimeout) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.observations = observations;
        this.requestTimeout = requestTimeout;
    }

    /** Publishes ToolSpec metadata hints as MCP tool annotations. */
    private static McpSchema.Tool toMcpTool(ToolSpec tool, Map<String, Object> schema) {
        McpSchema.Tool.Builder b = McpSchema.Tool.builder(tool.name(), schema).description(tool.description());
        Map<String, Object> meta = tool.metadata();
        if (meta.get(ToolSpec.META_TITLE) instanceof String title) {
            b.title(title);
        }
        if (meta.keySet().stream().anyMatch(k -> k.equals(ToolSpec.META_READ_ONLY) || k.equals(ToolSpec.META_DESTRUCTIVE)
                || k.equals(ToolSpec.META_IDEMPOTENT) || k.equals(ToolSpec.META_OPEN_WORLD))) {
            b.annotations(McpSchema.ToolAnnotations.builder()
                    .readOnlyHint(bool(meta, ToolSpec.META_READ_ONLY))
                    .destructiveHint(bool(meta, ToolSpec.META_DESTRUCTIVE))
                    .idempotentHint(bool(meta, ToolSpec.META_IDEMPOTENT))
                    .openWorldHint(bool(meta, ToolSpec.META_OPEN_WORLD))
                    .build());
        }
        return b.build();
    }

    private static Boolean bool(Map<String, Object> meta, String key) {
        return meta.get(key) instanceof Boolean value ? value : null;
    }

    /** Builds the SDK server on {@code spec}; the tool list is snapshotted now. */
    McpAsyncServer build(McpServer.AsyncSpecification<?> spec) {
        List<AsyncToolSpecification> specs = new ArrayList<>();
        for (ToolSpec tool : tools.listTools()) {
            Map<String, Object> schema = tool.inputSchema().isEmpty() ? EMPTY_OBJECT_SCHEMA : tool.inputSchema();
            specs.add(AsyncToolSpecification.builder()
                    .tool(toMcpTool(tool, schema))
                    .callHandler((exchange, request) -> call(exchange.transportContext(), request))
                    .build());
        }
        try {
            return spec.serverInfo(serverName, serverVersion)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(specs)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.MCP,
                    "cannot serve these tools over MCP: " + e.getMessage(), e);
        }
    }

    static ComplianceReport compliance(String transportFeature, List<String> wireRevisions) {
        Properties sdk = McpClientConnector.sdkProperties();
        return new ComplianceReport(ProtocolId.MCP, sdk.getProperty("mcp.sdk.name"), sdk.getProperty("mcp.sdk.version"),
                wireRevisions, null, McpFeatures.supported(transportFeature), McpFeatures.unsupported(transportFeature));
    }

    private Mono<McpSchema.CallToolResult> call(McpTransportContext context, McpSchema.CallToolRequest request) {
        CallerIdentity caller = context != null && context.get(CALLER_KEY) instanceof CallerIdentity c
                ? c : CallerIdentity.ANONYMOUS;
        ToolInvocation invocation = ToolInvocation.of(request.name(), request.arguments());
        Observation observation = new Observation(ProtocolId.MCP, "tools/call", request.name());
        Mono<McpSchema.CallToolResult> work = Mono.fromCallable(() -> toResult(tools.invoke(invocation, caller)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(requestTimeout, Mono.error(() -> new AgentWeaveException(ErrorCategory.TIMEOUT, ProtocolId.MCP,
                        "tool call '" + request.name() + "' timed out after " + requestTimeout)));
        return observed(observation, work).onErrorResume(ToolServerCore::toProtocolFailure);
    }

    private <T> Mono<T> observed(Observation observation, Mono<T> work) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            safely(() -> observations.onStart(observation));
            return work
                    .doOnSuccess(r -> safely(() -> observations.onSuccess(observation, since(start))))
                    .doOnError(e -> safely(() -> observations.onError(observation, since(start), e)));
        });
    }

    /** TOOL_NOT_FOUND → -32602; INVALID_ARGUMENTS → tool execution error; anything else → -32603. */
    static Mono<McpSchema.CallToolResult> toProtocolFailure(Throwable error) {
        if (error instanceof AgentWeaveException e) {
            String message = Objects.requireNonNullElse(e.getMessage(), e.category().name());
            if (e.category() == ErrorCategory.TOOL_NOT_FOUND) {
                return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build());
            }
            if (e.category() == ErrorCategory.INVALID_ARGUMENTS) {
                return Mono.just(McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build());
            }
            return Mono.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(message).build());
        }
        // Anything else is a bug in the tool source; its message may carry internals.
        return Mono.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message("internal error").build());
    }

    static McpSchema.CallToolResult toResult(ToolOutcome outcome) {
        List<McpSchema.Content> parts = new ArrayList<>(outcome.content().size());
        for (Content part : outcome.content()) {
            parts.add(toMcp(part));
        }
        McpSchema.CallToolResult.Builder result = McpSchema.CallToolResult.builder().content(parts).isError(outcome.error());
        if (outcome.structured() instanceof Map<?, ?> object) {
            result.structuredContent(object); // MCP structuredContent must be a JSON object
        }
        return result.build();
    }

    private static McpSchema.Content toMcp(Content part) {
        if (part instanceof Content.Text text) {
            return McpSchema.TextContent.builder(text.text()).build();
        }
        if (part instanceof Content.Data data) {
            if (data.mimeType().startsWith("image/")) {
                return McpSchema.ImageContent.builder(data.base64Data(), data.mimeType()).build();
            }
            if (data.mimeType().startsWith("audio/")) {
                return McpSchema.AudioContent.builder(data.base64Data(), data.mimeType()).build();
            }
            throw unsupported("binary content of type " + data.mimeType() + " (MCP tool results carry only image/* "
                    + "and audio/* data inline)");
        }
        if (part instanceof Content.Json json) {
            try {
                return McpSchema.TextContent.builder(McpJsonDefaults.getMapper().writeValueAsString(json.value())).build();
            } catch (IOException e) {
                throw new AgentWeaveException(ErrorCategory.INTERNAL, ProtocolId.MCP, "cannot serialize JSON content", e);
            }
        }
        if (part instanceof Content.Link link) {
            return McpSchema.ResourceLink.builder()
                    .uri(link.uri())
                    .name(link.name() != null && !link.name().isEmpty() ? link.name() : link.uri())
                    .mimeType(link.mimeType())
                    .build();
        }
        throw unsupported("content of type '" + ((Content.Unmapped) part).protocolType() + "'");
    }

    private static AgentWeaveException unsupported(String what) {
        return new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.MCP, "cannot return " + what + " over MCP");
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static void safely(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never break a call.
        }
    }
}
