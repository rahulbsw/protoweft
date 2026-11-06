package io.github.rahulbsw.protoweft.core.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes one parameter of an {@link AgentTool} method, or one component of a record argument. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface ToolParam {

    /** Argument name; defaults to the parameter name (requires compiling with {@code -parameters}). */
    String name() default "";

    String description() default "";

    /** Whether the argument must be supplied. {@code Optional<T>} parameters are never required. */
    boolean required() default true;
}
