package io.agentweave.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes a method as an MCP resource at a fixed URI; collected by {@link McpResources#scan}.
 * The method may declare a {@link io.agentweave.core.server.CallerIdentity} or
 * {@link McpResourceRequest} parameter and nothing else. Its return value is converted as
 * described on {@link McpResourceHandler}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResource {

    String uri();

    /** Defaults to the method name. */
    String name() default "";

    String title() default "";

    String description() default "";

    String mimeType() default "";
}
