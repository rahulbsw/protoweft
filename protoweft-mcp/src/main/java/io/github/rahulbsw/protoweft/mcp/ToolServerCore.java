package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    static final String CALLER_KEY = "io.github.rahulbsw.protoweft.caller";

    private static final Map<String, Object> EMPTY_OBJECT_SCHEMA = Map.of("type", "object");

    private final ToolSource tools;
    private final String serverName;
    private final String serverVersion;
    private final ObservationHandler observations;
    private final Duration requestTimeout;
    private final McpResources resources;
    private final McpPrompts prompts;

    ToolServerCore(ToolSource tools, String serverName, String serverVersion,
                   ObservationHandler observations, Duration requestTimeout) {
        this(tools, serverName, serverVersion, observations, requestTimeout, null, null);
    }

    /** {@code resources} and {@code prompts} may be {@code null}: their capabilities are then not declared. */
    ToolServerCore(ToolSource tools, String serverName, String serverVersion, ObservationHandler observations,
                   Duration requestTimeout, McpResources resources, McpPrompts prompts) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.observations = observations;
        this.requestTimeout = requestTimeout;
        this.resources = resources;
        this.prompts = prompts;
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

    /**
     * Builds the SDK server on {@code spec}; the tool list is snapshotted now. Call the returned
     * server's {@code close} through {@link Built#close()} so resource notifications stop.
     */
    Built build(McpServer.AsyncSpecification<?> spec) {
        List<AsyncToolSpecification> specs = new ArrayList<>();
        for (ToolSpec tool : tools.listTools()) {
            Map<String, Object> schema = tool.inputSchema().isEmpty() ? EMPTY_OBJECT_SCHEMA : tool.inputSchema();
            specs.add(AsyncToolSpecification.builder()
                    .tool(toMcpTool(tool, schema))
                    .callHandler(this::call)
                    .build());
        }
        McpSchema.ServerCapabilities.Builder capabilities = McpSchema.ServerCapabilities.builder().tools(false).logging();
        ServerAuthoring authoring = new ServerAuthoring(observations, requestTimeout);
        if (resources != null) {
            capabilities.resources(true, false);
            spec.resources(authoring.resources(resources)).resourceTemplates(authoring.templates(resources));
        }
        if (prompts != null) {
            capabilities.prompts(false);
            spec.prompts(authoring.prompts(prompts));
        }
        if ((resources != null && !resources.completers().isEmpty())
                || (prompts != null && !prompts.completers().isEmpty())) {
            capabilities.completions();
            spec.completions(authoring.completions(resources, prompts));
        }
        McpAsyncServer server;
        try {
            server = spec.serverInfo(serverName, serverVersion)
                    .capabilities(capabilities.build())
                    .tools(specs)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.MCP,
                    "cannot serve these tools over MCP: " + e.getMessage(), e);
        }
        return new Built(server, resources == null ? () -> { } : resources.bind(server));
    }

    /** A built SDK server and the action that unbinds it from its {@link McpResources}. */
    record Built(McpAsyncServer server, Runnable unbind) {
        void close() {
            unbind.run();
            server.close();
        }
    }

    /**
     * Builds a stateless SDK server on {@code spec}; the tool list is snapshotted now. The stateless
     * transport has no session, so there is nothing to bind: no resource-update notifications, no
     * progress, no logging.
     */
    BuiltStateless buildStateless(McpServer.StatelessAsyncSpecification spec) {
        List<McpStatelessServerFeatures.AsyncToolSpecification> specs = new ArrayList<>();
        for (ToolSpec tool : tools.listTools()) {
            Map<String, Object> schema = tool.inputSchema().isEmpty() ? EMPTY_OBJECT_SCHEMA : tool.inputSchema();
            specs.add(McpStatelessServerFeatures.AsyncToolSpecification.builder()
                    .tool(toMcpTool(tool, schema))
                    .callHandler(this::callStateless)
                    .build());
        }
        McpSchema.ServerCapabilities.Builder capabilities = McpSchema.ServerCapabilities.builder().tools(false);
        ServerAuthoring authoring = new ServerAuthoring(observations, requestTimeout);
        if (resources != null) {
            capabilities.resources(false, false); // stateless cannot subscribe or notify list changes
            spec.resources(authoring.statelessResources(resources))
                    .resourceTemplates(authoring.statelessTemplates(resources));
        }
        if (prompts != null) {
            capabilities.prompts(false);
            spec.prompts(authoring.statelessPrompts(prompts));
        }
        if ((resources != null && !resources.completers().isEmpty())
                || (prompts != null && !prompts.completers().isEmpty())) {
            capabilities.completions();
            spec.completions(authoring.statelessCompletions(resources, prompts));
        }
        McpStatelessAsyncServer server;
        try {
            server = spec.serverInfo(serverName, serverVersion)
                    .capabilities(capabilities.build())
                    .tools(specs)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.MCP,
                    "cannot serve these tools over MCP: " + e.getMessage(), e);
        }
        return new BuiltStateless(server);
    }

    /** A built stateless SDK server. Nothing to unbind: the transport holds no session state. */
    record BuiltStateless(McpStatelessAsyncServer server) {
        void close() {
            server.close();
        }
    }

    static ComplianceReport compliance(String transportFeature, List<String> wireRevisions) {
        Properties sdk = McpClientConnector.sdkProperties();
        return new ComplianceReport(ProtocolId.MCP, sdk.getProperty("mcp.sdk.name"), sdk.getProperty("mcp.sdk.version"),
                wireRevisions, null, McpFeatures.supported(McpFeatures.Role.SERVER, transportFeature),
                McpFeatures.unsupported(McpFeatures.Role.SERVER, transportFeature));
    }

    /** The caller the HTTP transport put in the context; anonymous over stdio. */
    static CallerIdentity caller(McpTransportContext context) {
        return context != null && context.get(CALLER_KEY) instanceof CallerIdentity c ? c : CallerIdentity.ANONYMOUS;
    }

    private Mono<McpSchema.CallToolResult> call(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {
        return invoke(caller(exchange.transportContext()),
                new SessionProgress(exchange, request.progressToken(), request.name()), request);
    }

    /** Stateless tool call: no session, so no progress or logging callbacks. */
    private Mono<McpSchema.CallToolResult> callStateless(McpTransportContext context, McpSchema.CallToolRequest request) {
        return invoke(caller(context), ToolProgress.NOOP, request);
    }

    private Mono<McpSchema.CallToolResult> invoke(CallerIdentity caller, ToolProgress progress,
                                                 McpSchema.CallToolRequest request) {
        ToolInvocation invocation = ToolInvocation.of(request.name(), request.arguments());
        Observation observation = new Observation(ProtocolId.MCP, "tools/call", request.name());
        Mono<McpSchema.CallToolResult> work = Mono.fromCallable(() -> toResult(tools.invoke(invocation, caller, progress).dropSubscription()))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(requestTimeout, Mono.error(() -> new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.MCP,
                        "tool call '" + request.name() + "' timed out after " + requestTimeout)));
        return observed(observations, observation, work).onErrorResume(ToolServerCore::toProtocolFailure);
    }

    static <T> Mono<T> observed(ObservationHandler observations, Observation observation, Mono<T> work) {
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
        if (error instanceof ProtoweftException e) {
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
            parts.add(McpContent.toMcp(part));
        }
        McpSchema.CallToolResult.Builder result = McpSchema.CallToolResult.builder().content(parts).isError(outcome.error());
        if (outcome.structured() instanceof Map<?, ?> object) {
            result.structuredContent(object); // MCP structuredContent must be a JSON object
        }
        return result.build();
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
