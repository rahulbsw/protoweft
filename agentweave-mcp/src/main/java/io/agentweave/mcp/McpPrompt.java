package io.agentweave.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes a method as an MCP prompt; collected by {@link McpPrompts#scan}. Each parameter is
 * a prompt argument named after it (or {@link io.agentweave.core.tool.ToolParam#name()}),
 * described and marked required by {@code @ToolParam}; {@code Optional<String>} arguments are
 * never required. Arguments convert to {@code String}, {@code int}, {@code long},
 * {@code double} or {@code boolean}. A {@link io.agentweave.core.server.CallerIdentity} or
 * {@link McpPromptRequest} parameter is injected instead. The return value is converted as
 * described on {@link McpPromptHandler}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpPrompt {

    /** Defaults to the method name. */
    String name() default "";

    String title() default "";

    String description() default "";
}
