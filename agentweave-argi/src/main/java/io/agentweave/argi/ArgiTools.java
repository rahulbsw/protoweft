package io.agentweave.argi;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.springai.AgentWeaveToolCallbacks;
import io.github.agentic.ai.graph.RunnableConfig;

import java.util.Map;
import java.util.Objects;

/**
 * AgentWeave tools in ARGI agents. ARGI 2.1.0-RC1 takes Spring AI tool callbacks, so the provider
 * is {@code agentweave-spring-ai}'s {@link AgentWeaveToolCallbacks}:
 *
 * <pre>{@code
 * ReactAgent agent = ReactAgent.builder()
 *         .name("assistant")
 *         .model(chatModel)
 *         .toolCallbackProviders(ArgiTools.callbackProvider(registry))   // weather.forecast -> weather__forecast
 *         .saver(new MemorySaver())
 *         .build();
 *
 * // per run: the caller travels in the RunnableConfig metadata
 * agent.call("Weather in Paris?", ArgiTools.withCaller(RunnableConfig.builder().threadId(id), caller).build());
 * }</pre>
 *
 * <p><b>Caller.</b> ARGI builds each tool's {@code ToolContext} from the agent's static
 * {@code Builder.toolContext(Map)} plus the run's {@code RunnableConfig} metadata, so the
 * {@link CallerIdentity} under {@link #CALLER_KEY} ({@code agentweave.caller}) reaches the tool
 * either way: a fixed caller with {@code .toolContext(Map.of(ArgiTools.CALLER_KEY, caller))}, or a
 * per-run caller with {@link #withCaller}. {@link ArgiAgents} does the latter for every A2A
 * request. Without either, tools are called anonymously.
 *
 * <p><b>Listing.</b> ARGI reads {@code getToolCallbacks()} once, when the agent is built, so the
 * tools are a snapshot: tools a source adds later need a new agent.
 */
public final class ArgiTools {

    /** The tool-context and {@code RunnableConfig} metadata key holding the {@link CallerIdentity}. */
    public static final String CALLER_KEY = AgentWeaveToolCallbacks.CALLER_KEY;

    private ArgiTools() {
    }

    /**
     * The tools of any AgentWeave {@link ToolSource} (MCP, UTCP, A2A, local tools, a
     * {@code ToolRegistry}) for {@code ReactAgent.builder().toolCallbackProviders(..)}, with
     * provider-safe names ({@code .} becomes {@code __}) and the caller read from
     * {@link #CALLER_KEY}. Use {@link AgentWeaveToolCallbacks#builder} for filters or other names.
     */
    public static AgentWeaveToolCallbacks callbackProvider(ToolSource source) {
        return AgentWeaveToolCallbacks.from(Objects.requireNonNull(source, "source"));
    }

    /** Adds the caller to a run's metadata, from where ARGI copies it into every tool's context. */
    public static RunnableConfig.Builder withCaller(RunnableConfig.Builder config, CallerIdentity caller) {
        Objects.requireNonNull(config, "config");
        return config.addMetadata(CALLER_KEY, caller == null ? CallerIdentity.ANONYMOUS : caller);
    }

    /**
     * The caller in an ARGI context map (a {@code ModelRequest} context or a tool context), else
     * anonymous.
     */
    public static CallerIdentity callerFrom(Map<String, Object> context) {
        Object value = context == null ? null : context.get(CALLER_KEY);
        return value instanceof CallerIdentity identity ? identity : CallerIdentity.ANONYMOUS;
    }
}
