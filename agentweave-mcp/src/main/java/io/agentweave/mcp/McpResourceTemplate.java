package io.agentweave.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes a method as an MCP resource template; collected by {@link McpResources#scan}.
 * Each template variable binds to the parameter of the same name (or
 * {@link io.agentweave.core.tool.ToolParam#name()}), converted to {@code String}, {@code int},
 * {@code long}, {@code double} or {@code boolean}. The method may also declare a
 * {@link io.agentweave.core.server.CallerIdentity} or {@link McpResourceRequest} parameter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResourceTemplate {

    /** An RFC 6570 template with simple {@code {variable}} expressions. */
    String uriTemplate();

    /** Defaults to the method name. */
    String name() default "";

    String title() default "";

    String description() default "";

    String mimeType() default "";
}
