package io.github.rahulbsw.protoweft.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes a method as the {@link McpCompleter} of one prompt argument (collected by
 * {@link McpPrompts#scan}) or one template variable (collected by {@link McpResources#scan}).
 * Set exactly one of {@link #prompt()} and {@link #uriTemplate()}. The method returns a
 * {@code List<String>} and takes an {@link McpCompletionRequest}, or a single {@code String}
 * (the typed value).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpComplete {

    /** The prompt whose argument is completed. */
    String prompt() default "";

    /** The resource template whose variable is completed. */
    String uriTemplate() default "";

    /** The argument or variable name. */
    String argument();
}
