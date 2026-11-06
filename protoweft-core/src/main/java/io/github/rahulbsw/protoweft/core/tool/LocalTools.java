package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tools implemented in this JVM, defined once and served over any Protoweft server
 * protocol (MCP server, UTCP manual, …) or used directly as a {@link ToolSource}.
 *
 * <pre>{@code
 * LocalTools tools = LocalTools.builder()
 *         .scan(new WeatherTools())                       // @AgentTool methods
 *         .tool("ping", "Health check", Map.of("type", "object"),
 *               (args, caller) -> ToolOutcome.text("pong"))
 *         .build();
 * }</pre>
 *
 * <p>Immutable and thread-safe once built; tool implementations must be thread-safe.
 */
public final class LocalTools implements ToolSource {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Map<String, LocalTool> tools;

    private LocalTools(Map<String, LocalTool> tools) {
        this.tools = Map.copyOf(tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** One tool: its description and implementation. */
    public record LocalTool(ToolSpec spec, ProgressAwareToolHandler handler) {
        public LocalTool {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(handler, "handler");
        }

        /** A tool whose handler doesn't report progress. */
        public LocalTool(ToolSpec spec, ToolHandler handler) {
            this(spec, adapt(Objects.requireNonNull(handler, "handler")));
        }

        private static ProgressAwareToolHandler adapt(ToolHandler handler) {
            return (args, caller, progress) -> handler.handle(args, caller);
        }
    }

    @Override
    public List<ToolSpec> listTools() {
        return tools.values().stream().map(LocalTool::spec)
                .sorted(java.util.Comparator.comparing(ToolSpec::name)).toList();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        return invoke(invocation, CallerIdentity.ANONYMOUS);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller) {
        return invoke(invocation, caller, ToolProgress.NOOP);
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress) {
        LocalTool tool = tools.get(invocation.toolName());
        if (tool == null) {
            throw new ProtoweftException(ErrorCategory.TOOL_NOT_FOUND, null, "unknown tool: " + invocation.toolName());
        }
        try {
            ToolOutcome outcome = tool.handler().handle(invocation.arguments(),
                    caller == null ? CallerIdentity.ANONYMOUS : caller, progress == null ? ToolProgress.NOOP : progress);
            return outcome == null ? new ToolOutcome(List.of(), false, null) : outcome;
        } catch (ProtoweftException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** Builds {@link LocalTools}. */
    public static final class Builder {

        private final Map<String, LocalTool> tools = new LinkedHashMap<>();

        /** Adds a programmatic tool. */
        public Builder tool(String name, String description, Map<String, Object> inputSchema, ToolHandler handler) {
            return add(new LocalTool(new ToolSpec(name, description, inputSchema), handler));
        }

        /** Adds a programmatic tool that reports progress or log messages. */
        public Builder tool(String name, String description, Map<String, Object> inputSchema,
                            ProgressAwareToolHandler handler) {
            return add(new LocalTool(new ToolSpec(name, description, inputSchema), handler));
        }

        public Builder add(LocalTool tool) {
            String name = tool.spec().name();
            if (!TOOL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("tool name must match " + TOOL_NAME + ": " + name);
            }
            if (tools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
            return this;
        }

        /** Adds every tool from another source, for example LangChain4j-annotated tools. */
        public Builder addAll(List<LocalTool> more) {
            more.forEach(this::add);
            return this;
        }

        /**
         * Adds every public {@link AgentTool}-annotated method of each object.
         *
         * @throws IllegalArgumentException if a method has an unsupported signature
         */
        public Builder scan(Object... targets) {
            for (Object target : targets) {
                Objects.requireNonNull(target, "target");
                List<Method> methods = new ArrayList<>();
                for (Method m : target.getClass().getMethods()) {
                    if (m.isAnnotationPresent(AgentTool.class) && !Modifier.isStatic(m.getModifiers())) {
                        methods.add(m);
                    }
                }
                methods.sort(java.util.Comparator.comparing(Method::getName));
                for (Method m : methods) {
                    add(fromMethod(target, m));
                }
            }
            return this;
        }

        public LocalTools build() {
            return new LocalTools(tools);
        }

        private static LocalTool fromMethod(Object target, Method method) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
            String where = target.getClass().getSimpleName() + "." + method.getName();
            Parameter[] params = method.getParameters();
            String[] names = new String[params.length];
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (int i = 0; i < params.length; i++) {
                Parameter p = params[i];
                if (p.getType() == CallerIdentity.class || p.getType() == ToolProgress.class) {
                    continue;
                }
                ToolParam tp = p.getAnnotation(ToolParam.class);
                String name = tp != null && !tp.name().isEmpty() ? tp.name() : p.getName();
                if (!p.isNamePresent() && (tp == null || tp.name().isEmpty())) {
                    throw new IllegalArgumentException(where + ": parameter names are not available; compile with "
                            + "-parameters or set @ToolParam(name = ...)");
                }
                names[i] = name;
                try {
                    properties.put(name, JavaTypes.describe(JavaTypes.schemaFor(p.getParameterizedType()), tp));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + ", parameter '" + name + "': " + e.getMessage(), e);
                }
                if (JavaTypes.isRequired(p.getType(), tp)) {
                    required.add(name);
                }
            }
            method.setAccessible(true);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (annotation.tags().length > 0) {
                metadata.put(ToolSpec.META_TAGS, List.of(annotation.tags()));
            }
            if (annotation.readOnly()) {
                metadata.put(ToolSpec.META_READ_ONLY, true);
            }
            if (annotation.destructive()) {
                metadata.put(ToolSpec.META_DESTRUCTIVE, true);
            }
            ToolSpec spec = new ToolSpec(toolName, annotation.description(),
                    JavaTypes.objectSchema(properties, required), metadata);
            return new LocalTool(spec, (args, caller, progress) -> {
                Object[] values = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i].getType() == CallerIdentity.class) {
                        values[i] = caller;
                        continue;
                    }
                    if (params[i].getType() == ToolProgress.class) {
                        values[i] = progress;
                        continue;
                    }
                    Object raw = args.get(names[i]);
                    if (raw == null && required.contains(names[i])) {
                        throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, null,
                                "missing required argument '" + names[i] + "' for tool " + toolName);
                    }
                    try {
                        values[i] = JavaTypes.bind(raw, params[i].getParameterizedType());
                    } catch (IllegalArgumentException e) {
                        throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, null,
                                "argument '" + names[i] + "' for tool " + toolName + ": " + e.getMessage(), e);
                    }
                }
                try {
                    return toOutcome(method.invoke(target, values));
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof ProtoweftException awe) {
                        throw awe;
                    }
                    throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
                }
            });
        }

        private static ToolOutcome toOutcome(Object result) {
            if (result == null) {
                return new ToolOutcome(List.of(), false, null);
            }
            if (result instanceof ToolOutcome outcome) {
                return outcome;
            }
            if (result instanceof Content content) {
                return new ToolOutcome(List.of(content), false, null);
            }
            Object plain = JavaTypes.toPlain(result);
            if (plain instanceof String || plain instanceof Number || plain instanceof Boolean) {
                return ToolOutcome.text(String.valueOf(plain));
            }
            return new ToolOutcome(List.of(new Content.Text(PlainJson.write(plain))), false, plain);
        }
    }
}
