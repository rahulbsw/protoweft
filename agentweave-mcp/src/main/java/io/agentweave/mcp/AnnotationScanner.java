package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolParam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns {@link McpResource}, {@link McpResourceTemplate}, {@link McpPrompt} and {@link McpComplete} methods into handlers. */
final class AnnotationScanner {

    private AnnotationScanner() {
    }

    static void scanResources(Object target, McpResources.Builder builder) {
        for (Method m : annotated(target)) {
            String where = target.getClass().getSimpleName() + "." + m.getName();
            McpResource fixed = m.getAnnotation(McpResource.class);
            McpResourceTemplate template = m.getAnnotation(McpResourceTemplate.class);
            if (fixed != null && template != null) {
                throw new IllegalArgumentException(where + ": both @McpResource and @McpResourceTemplate");
            }
            if (fixed != null) {
                Binder binder = new Binder(m, where, List.of());
                builder.resource(new McpResourceInfo(fixed.uri(), or(fixed.name(), m.getName()), blank(fixed.title()),
                        blank(fixed.description()), blank(fixed.mimeType())),
                        req -> invoke(target, m, binder.bind(req, req.caller(), req.variables())));
            } else if (template != null) {
                List<String> variables = new io.modelcontextprotocol.util.DefaultMcpUriTemplateManager(
                        template.uriTemplate()).getVariableNames();
                Binder binder = new Binder(m, where, variables);
                builder.template(new McpTemplateInfo(template.uriTemplate(), or(template.name(), m.getName()),
                        blank(template.title()), blank(template.description()), blank(template.mimeType())),
                        req -> invoke(target, m, binder.bind(req, req.caller(), req.variables())));
            }
            McpComplete complete = m.getAnnotation(McpComplete.class);
            if (complete != null && !complete.uriTemplate().isEmpty()) {
                requireOneRef(complete, where);
                builder.completion(complete.uriTemplate(), complete.argument(), completer(target, m, where));
            }
        }
    }

    static void scanPrompts(Object target, McpPrompts.Builder builder) {
        for (Method m : annotated(target)) {
            String where = target.getClass().getSimpleName() + "." + m.getName();
            McpPrompt prompt = m.getAnnotation(McpPrompt.class);
            if (prompt != null) {
                Binder binder = new Binder(m, where, null);
                builder.prompt(new McpPromptInfo(or(prompt.name(), m.getName()), blank(prompt.title()),
                        blank(prompt.description()), binder.arguments()),
                        req -> invoke(target, m, binder.bind(req, req.caller(), req.arguments())));
            }
            McpComplete complete = m.getAnnotation(McpComplete.class);
            if (complete != null && !complete.prompt().isEmpty()) {
                requireOneRef(complete, where);
                builder.completion(complete.prompt(), complete.argument(), completer(target, m, where));
            }
        }
    }

    private static void requireOneRef(McpComplete complete, String where) {
        if (!complete.prompt().isEmpty() && !complete.uriTemplate().isEmpty()) {
            throw new IllegalArgumentException(where + ": @McpComplete sets both prompt and uriTemplate");
        }
    }

    private static McpCompleter completer(Object target, Method m, String where) {
        if (!Collection.class.isAssignableFrom(m.getReturnType())) {
            throw new IllegalArgumentException(where + ": an @McpComplete method must return a List<String>");
        }
        Parameter[] params = m.getParameters();
        for (Parameter p : params) {
            if (p.getType() != McpCompletionRequest.class && p.getType() != String.class
                    && p.getType() != CallerIdentity.class) {
                throw new IllegalArgumentException(where + ": an @McpComplete method takes an McpCompletionRequest, "
                        + "the typed String value or a CallerIdentity");
            }
        }
        m.setAccessible(true);
        return request -> {
            Object[] values = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> type = params[i].getType();
                values[i] = type == McpCompletionRequest.class ? request
                        : type == CallerIdentity.class ? request.caller() : request.value();
            }
            Collection<?> result = (Collection<?>) invoke(target, m, values);
            return result == null ? List.of() : result.stream().map(String::valueOf).toList();
        };
    }

    private static List<Method> annotated(Object target) {
        List<Method> methods = new ArrayList<>();
        for (Method m : target.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.isAnnotationPresent(McpResource.class) || m.isAnnotationPresent(McpResourceTemplate.class)
                    || m.isAnnotationPresent(McpPrompt.class) || m.isAnnotationPresent(McpComplete.class)) {
                m.setAccessible(true);
                methods.add(m);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    private static Object invoke(Object target, Method m, Object[] values) throws Exception {
        try {
            return m.invoke(target, values);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
        }
    }

    private static String or(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static String blank(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * Binds a method's parameters: the caller and the request object are injected, every other
     * parameter takes a named string value converted to its type.
     */
    private static final class Binder {

        private final Parameter[] params;
        private final String[] names;
        private final List<McpPromptArgument> arguments = new ArrayList<>();

        /** @param allowed the names values may have, or {@code null} for any (prompt arguments) */
        Binder(Method m, String where, List<String> allowed) {
            this.params = m.getParameters();
            this.names = new String[params.length];
            for (int i = 0; i < params.length; i++) {
                Parameter p = params[i];
                if (isInjected(p.getType())) {
                    continue;
                }
                ToolParam tp = p.getAnnotation(ToolParam.class);
                if (!p.isNamePresent() && (tp == null || tp.name().isEmpty())) {
                    throw new IllegalArgumentException(where + ": parameter names are not available; compile with "
                            + "-parameters or set @ToolParam(name = ...)");
                }
                String name = tp != null && !tp.name().isEmpty() ? tp.name() : p.getName();
                if (allowed != null && !allowed.contains(name)) {
                    throw new IllegalArgumentException(where + ": parameter '" + name + "' is not one of "
                            + (allowed.isEmpty() ? "the injectable types" : "the template variables " + allowed));
                }
                if (!isConvertible(p.getParameterizedType())) {
                    throw new IllegalArgumentException(where + ", parameter '" + name + "': unsupported type "
                            + p.getParameterizedType().getTypeName());
                }
                names[i] = name;
                boolean required = p.getType() != Optional.class && (tp == null || tp.required());
                arguments.add(new McpPromptArgument(name, null,
                        tp == null || tp.description().isEmpty() ? null : tp.description(), required));
            }
        }

        List<McpPromptArgument> arguments() {
            return List.copyOf(arguments);
        }

        Object[] bind(Object request, CallerIdentity caller, Map<String, String> values) {
            Object[] out = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> type = params[i].getType();
                if (type == CallerIdentity.class) {
                    out[i] = caller;
                } else if (isInjected(type)) {
                    out[i] = type.isInstance(request) ? request : null;
                } else {
                    out[i] = convert(names[i], values.get(names[i]), params[i].getParameterizedType());
                }
            }
            return out;
        }

        private static boolean isInjected(Class<?> type) {
            return type == CallerIdentity.class || type == McpResourceRequest.class || type == McpPromptRequest.class;
        }

        private static boolean isConvertible(Type type) {
            if (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
                return isConvertible(pt.getActualTypeArguments()[0]);
            }
            return type == String.class || type == int.class || type == Integer.class || type == long.class
                    || type == Long.class || type == double.class || type == Double.class || type == boolean.class
                    || type == Boolean.class;
        }

        private static Object convert(String name, String value, Type type) {
            if (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
                return Optional.ofNullable(value == null ? null : convert(name, value, pt.getActualTypeArguments()[0]));
            }
            if (value == null) {
                if (type instanceof Class<?> c && c.isPrimitive()) {
                    throw invalid("missing value for '" + name + "'");
                }
                return null;
            }
            try {
                if (type == int.class || type == Integer.class) {
                    return Integer.valueOf(value.trim());
                }
                if (type == long.class || type == Long.class) {
                    return Long.valueOf(value.trim());
                }
                if (type == double.class || type == Double.class) {
                    return Double.valueOf(value.trim());
                }
            } catch (NumberFormatException e) {
                throw invalid("'" + name + "' must be a number: " + value);
            }
            if (type == boolean.class || type == Boolean.class) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw invalid("'" + name + "' must be true or false: " + value);
                }
                return Boolean.valueOf(value);
            }
            return value;
        }

        private static AgentWeaveException invalid(String message) {
            return new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.MCP, message);
        }
    }
}
