package io.github.rahulbsw.protoweft.core.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public method as a tool. {@link LocalTools#builder()}{@code .scan(object)} turns
 * every annotated method into a tool whose JSON input schema is generated from the method's
 * parameters. The same tool can then be served over any Protoweft server protocol.
 *
 * <pre>{@code
 * class Weather {
 *     @AgentTool(description = "Current temperature in a city, in Celsius")
 *     double temperature(@ToolParam(description = "City name") String city) { ... }
 * }
 * }</pre>
 *
 * <p>Supported parameter types: {@code String}, primitives and their wrappers,
 * {@code BigDecimal}/{@code BigInteger}, enums, records, {@code List}/{@code Set}/arrays,
 * {@code Map<String, T>}, and {@code Optional<T>} (optional parameter). A parameter of type
 * {@link io.github.rahulbsw.protoweft.core.server.CallerIdentity} receives the authenticated caller and is
 * not part of the schema. Parameter names come from {@link ToolParam#name()} or, when the
 * code is compiled with {@code -parameters}, from the source.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /** Tool name; defaults to the method name. Must match {@code [A-Za-z0-9_-]{1,64}}. */
    String name() default "";

    /** What the tool does, written for a model deciding whether to call it. */
    String description();

    /** Keywords that help discovery find the tool. */
    String[] tags() default {};

    /** The tool doesn't modify its environment. Published as MCP {@code readOnlyHint}. */
    boolean readOnly() default false;

    /** The tool may perform destructive updates. Published as MCP {@code destructiveHint}. */
    boolean destructive() default false;
}
