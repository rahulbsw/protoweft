package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link ToolContext}s a server protocol provides for one call, keyed by their interface type.
 * Immutable.
 */
public final class ToolContexts {

    /** No contexts: what every call gets unless its protocol provides some. */
    public static final ToolContexts EMPTY = new ToolContexts(Map.of());

    private final Map<Class<?>, ToolContext> contexts;

    private ToolContexts(Map<Class<?>, ToolContext> contexts) {
        this.contexts = contexts;
    }

    /** Contexts holding one value under {@code type}. */
    public static <T extends ToolContext> ToolContexts of(Class<T> type, T value) {
        return EMPTY.with(type, value);
    }

    /**
     * A copy that also holds {@code value} under {@code type}, replacing any earlier value.
     *
     * @throws IllegalArgumentException if {@code type} is not an interface
     */
    public <T extends ToolContext> ToolContexts with(Class<T> type, T value) {
        requireInterface(type);
        Objects.requireNonNull(value, "value");
        Map<Class<?>, ToolContext> copy = new LinkedHashMap<>(contexts);
        copy.put(type, value);
        return new ToolContexts(Map.copyOf(copy));
    }

    /** The context provided under {@code type}, if any. */
    public <T extends ToolContext> Optional<T> get(Class<T> type) {
        return Optional.ofNullable(type.cast(contexts.get(type)));
    }

    /**
     * The context provided under {@code type}, or the unavailable stand-in described in
     * {@link ToolContext}. Never {@code null}.
     *
     * @throws IllegalArgumentException if {@code type} is not an interface
     */
    public <T extends ToolContext> T resolve(Class<T> type) {
        requireInterface(type);
        return get(type).orElseGet(() -> unavailable(type));
    }

    /** The stand-in for a context the serving protocol doesn't provide. */
    public static <T extends ToolContext> T unavailable(Class<T> type) {
        requireInterface(type);
        String name = type.getSimpleName();
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> unavailableCall(self, name, method, args));
        return type.cast(proxy);
    }

    private static Object unavailableCall(Object self, String name, Method method, Object[] args) {
        switch (method.getName()) {
            case "available":
                if (method.getParameterCount() == 0) {
                    return false;
                }
                break;
            case "toString":
                if (method.getParameterCount() == 0) {
                    return "unavailable " + name;
                }
                break;
            case "hashCode":
                if (method.getParameterCount() == 0) {
                    return System.identityHashCode(self);
                }
                break;
            case "equals":
                if (method.getParameterCount() == 1) {
                    return self == args[0];
                }
                break;
            default:
                break;
        }
        throw new ProtoweftException(ErrorCategory.UNSUPPORTED, null,
                name + " is not available: the protocol serving this call does not provide it");
    }

    private static void requireInterface(Class<?> type) {
        Objects.requireNonNull(type, "type");
        if (!type.isInterface() || !ToolContext.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("a tool context type must be an interface extending ToolContext: "
                    + type.getName());
        }
    }
}
