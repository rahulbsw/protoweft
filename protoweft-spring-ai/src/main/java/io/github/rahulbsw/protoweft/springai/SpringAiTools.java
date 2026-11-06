package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns Spring AI tools ({@link ToolCallback}s, for example {@code @Tool} methods through
 * {@code MethodToolCallbackProvider}) into Protoweft local tools, so the same tools can be
 * served over any Protoweft server protocol (MCP server, UTCP manual, …).
 *
 * <pre>{@code
 * LocalTools tools = LocalTools.builder()
 *         .addAll(SpringAiTools.from(MethodToolCallbackProvider.builder().toolObjects(new WeatherTools()).build()))
 *         .build();
 * }</pre>
 *
 * <p>Each call runs {@link ToolCallback#call(String, ToolContext)} with the arguments as JSON and a
 * tool context holding the Protoweft caller ({@link #CALLER_KEY}) and progress reporter
 * ({@link #PROGRESS_KEY}); a {@code @Tool} method reads them by declaring a {@code ToolContext}
 * parameter. The callback's string result becomes the outcome's text; a JSON object or array
 * result is also the structured payload, and a JSON string result is unquoted.
 */
public final class SpringAiTools {

    /** {@link ToolContext} key under which the calling {@link CallerIdentity} is passed. */
    public static final String CALLER_KEY = ProtoweftToolCallbacks.CALLER_KEY;
    /** {@link ToolContext} key under which the call's {@link ToolProgress} is passed. */
    public static final String PROGRESS_KEY = ProtoweftToolCallbacks.PROGRESS_KEY;

    private static final Pattern LOCAL_TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private SpringAiTools() {
    }

    /** One local tool per callback of each provider. */
    public static List<LocalTools.LocalTool> from(ToolCallbackProvider... providers) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallbackProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            callbacks.addAll(List.of(provider.getToolCallbacks()));
        }
        return from(callbacks.toArray(ToolCallback[]::new));
    }

    /**
     * One local tool per callback.
     *
     * @throws IllegalArgumentException if a tool name is not a valid Protoweft local tool name
     *                                  ({@code [A-Za-z0-9_-]{1,64}}; set {@code @Tool(name = ...)}),
     *                                  appears twice, or its input schema is not a JSON object
     */
    public static List<LocalTools.LocalTool> from(ToolCallback... callbacks) {
        List<LocalTools.LocalTool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ToolCallback callback : callbacks) {
            Objects.requireNonNull(callback, "callback");
            ToolDefinition definition = callback.getToolDefinition();
            String name = definition.name();
            if (!LOCAL_TOOL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("tool name must match " + LOCAL_TOOL_NAME
                        + " (set @Tool(name = ...)): " + name);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
            Map<String, Object> schema;
            try {
                schema = Json.parseObject(definition.inputSchema());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("tool '" + name + "': input schema is not a JSON object: "
                        + e.getMessage(), e);
            }
            Map<String, Object> metadata = callback.getToolMetadata().returnDirect()
                    ? Map.of("springAi.returnDirect", true) : Map.of();
            ToolSpec spec = new ToolSpec(name, definition.description(), schema, metadata);
            tools.add(new LocalTools.LocalTool(spec, (args, caller, progress) -> invoke(callback, args, caller, progress)));
        }
        return List.copyOf(tools);
    }

    private static ToolOutcome invoke(ToolCallback callback, Map<String, Object> args, CallerIdentity caller,
                                      ToolProgress progress) {
        Map<String, Object> context = new HashMap<>();
        context.put(CALLER_KEY, caller == null ? CallerIdentity.ANONYMOUS : caller);
        context.put(PROGRESS_KEY, progress == null ? ToolProgress.NOOP : progress);
        String result;
        try {
            result = callback.call(Json.write(args), new ToolContext(context));
        } catch (ToolExecutionException e) {
            if (e.getCause() instanceof ProtoweftException awe) {
                throw awe;
            }
            return ToolOutcome.failure(message(e));
        }
        // Any other exception propagates; LocalTools reports it as a failure outcome (an
        // ProtoweftException propagates unchanged), as for every local tool handler.
        return toOutcome(result);
    }

    /** A JSON string is unquoted; a JSON object or array is also kept as the structured payload. */
    static ToolOutcome toOutcome(String result) {
        if (result == null) {
            return new ToolOutcome(List.of(), false, null);
        }
        Object parsed = Json.parseOrNull(result);
        if (parsed instanceof String s) {
            return ToolOutcome.text(s);
        }
        if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
            return new ToolOutcome(List.of(new Content.Text(result)), false, parsed);
        }
        return ToolOutcome.text(result);
    }

    private static String message(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }
}
