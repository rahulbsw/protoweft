package io.agentweave.core.tool;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps Java types to JSON Schema, binds plain JSON values (maps/lists/scalars) to Java
 * values, and converts Java results back to plain values. Only the types documented on
 * {@link AgentTool} are supported; anything else is rejected at scan time.
 */
final class JavaTypes {

    private static final int MAX_DEPTH = 16;

    private JavaTypes() {
    }

    // ---------------------------------------------------------------- schema

    static Map<String, Object> schemaFor(Type type) {
        return schemaFor(type, 0);
    }

    private static Map<String, Object> schemaFor(Type type, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("type nests too deeply (recursive record?): " + type.getTypeName());
        }
        Class<?> raw = rawClass(type);
        if (raw == Optional.class) {
            return schemaFor(typeArg(type, 0), depth + 1);
        }
        if (isString(raw)) {
            return Map.of("type", "string");
        }
        if (isInteger(raw)) {
            return Map.of("type", "integer");
        }
        if (isNumber(raw)) {
            return Map.of("type", "number");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (raw.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object c : raw.getEnumConstants()) {
                names.add(((Enum<?>) c).name());
            }
            return Map.of("type", "string", "enum", List.copyOf(names));
        }
        if (raw.isArray()) {
            return Map.of("type", "array", "items", schemaFor(componentType(type), depth + 1));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return Map.of("type", "array", "items", schemaFor(typeArg(type, 0), depth + 1));
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type keyType = typeArg(type, 0);
            if (keyType != Object.class && rawClass(keyType) != String.class) {
                throw new IllegalArgumentException("map keys must be String: " + type.getTypeName());
            }
            Type valueType = typeArg(type, 1);
            return valueType == Object.class
                    ? Map.of("type", "object")
                    : Map.of("type", "object", "additionalProperties", schemaFor(valueType, depth + 1));
        }
        if (raw.isRecord()) {
            return recordSchema(raw, depth);
        }
        if (raw == Object.class) {
            return Map.of();
        }
        throw new IllegalArgumentException("unsupported tool parameter type " + type.getTypeName()
                + "; use a scalar, enum, record, List, Map<String, T> or Optional");
    }

    private static Map<String, Object> recordSchema(Class<?> record, int depth) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent rc : record.getRecordComponents()) {
            ToolParam param = rc.getAnnotation(ToolParam.class);
            String name = param != null && !param.name().isEmpty() ? param.name() : rc.getName();
            properties.put(name, describe(schemaFor(rc.getGenericType(), depth + 1), param));
            if (isRequired(rc.getType(), param)) {
                required.add(name);
            }
        }
        return objectSchema(properties, required);
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return schema;
    }

    static Map<String, Object> describe(Map<String, Object> schema, ToolParam param) {
        if (param == null || param.description().isEmpty()) {
            return schema;
        }
        Map<String, Object> withDescription = new LinkedHashMap<>(schema);
        withDescription.put("description", param.description());
        return withDescription;
    }

    static boolean isRequired(Class<?> type, ToolParam param) {
        return type != Optional.class && (param == null || param.required());
    }

    // ---------------------------------------------------------------- binding

    /**
     * Converts a plain JSON value to {@code target}.
     *
     * @throws IllegalArgumentException if the value cannot represent the target type
     */
    static Object bind(Object value, Type target) {
        Class<?> raw = rawClass(target);
        if (raw == Optional.class) {
            return Optional.ofNullable(value == null ? null : bind(value, typeArg(target, 0)));
        }
        if (value == null) {
            if (raw.isPrimitive()) {
                throw new IllegalArgumentException("null is not a valid " + raw.getName());
            }
            return null;
        }
        if (raw == Object.class) {
            return value;
        }
        if (isString(raw)) {
            if (value instanceof Map || value instanceof Collection) {
                throw new IllegalArgumentException("expected a string but got " + describe(value));
            }
            String s = String.valueOf(value);
            if (raw == char.class || raw == Character.class) {
                if (s.length() != 1) {
                    throw new IllegalArgumentException("expected a single character but got \"" + s + "\"");
                }
                return s.charAt(0);
            }
            return s;
        }
        if (isInteger(raw) || isNumber(raw)) {
            return bindNumber(value, raw);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                return Boolean.parseBoolean(s);
            }
            throw new IllegalArgumentException("expected a boolean but got " + describe(value));
        }
        if (raw.isEnum()) {
            String s = String.valueOf(value);
            for (Object c : raw.getEnumConstants()) {
                if (((Enum<?>) c).name().equals(s)) {
                    return c;
                }
            }
            for (Object c : raw.getEnumConstants()) {
                if (((Enum<?>) c).name().equalsIgnoreCase(s)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("\"" + s + "\" is not one of " + List.of(raw.getEnumConstants()));
        }
        if (raw.isArray()) {
            List<?> list = asList(value);
            Type component = componentType(target);
            Object array = Array.newInstance(rawClass(component), list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, bind(list.get(i), component));
            }
            return array;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Collection<Object> out = Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object element : asList(value)) {
                out.add(bind(element, typeArg(target, 0)));
            }
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> out = new LinkedHashMap<>();
            asMap(value).forEach((k, v) -> out.put(String.valueOf(k), bind(v, typeArg(target, 1))));
            return out;
        }
        if (raw.isRecord()) {
            return bindRecord(asMap(value), raw);
        }
        throw new IllegalArgumentException("unsupported type " + target.getTypeName());
    }

    private static Object bindRecord(Map<?, ?> values, Class<?> record) {
        RecordComponent[] components = record.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            ToolParam param = rc.getAnnotation(ToolParam.class);
            String name = param != null && !param.name().isEmpty() ? param.name() : rc.getName();
            Object v = values.get(name);
            if (v == null && !values.containsKey(name) && isRequired(rc.getType(), param)) {
                throw new IllegalArgumentException("missing required field '" + name + "'");
            }
            try {
                args[i] = bind(v, rc.getGenericType());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("field '" + name + "': " + e.getMessage(), e);
            }
            types[i] = rc.getType();
        }
        try {
            var ctor = record.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalArgumentException("cannot create " + record.getSimpleName() + ": " + cause.getMessage(), e);
        }
    }

    private static Object bindNumber(Object value, Class<?> raw) {
        BigDecimal n;
        if (value instanceof BigDecimal bd) {
            n = bd;
        } else if (value instanceof BigInteger bi) {
            n = new BigDecimal(bi);
        } else if (value instanceof Number num) {
            n = (num instanceof Double || num instanceof Float) ? BigDecimal.valueOf(num.doubleValue())
                    : BigDecimal.valueOf(num.longValue());
        } else if (value instanceof String s) {
            try {
                n = new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected a number but got \"" + s + "\"");
            }
        } else {
            throw new IllegalArgumentException("expected a number but got " + describe(value));
        }
        try {
            if (raw == int.class || raw == Integer.class) {
                return n.intValueExact();
            }
            if (raw == long.class || raw == Long.class) {
                return n.longValueExact();
            }
            if (raw == short.class || raw == Short.class) {
                return n.shortValueExact();
            }
            if (raw == byte.class || raw == Byte.class) {
                return n.byteValueExact();
            }
            if (raw == BigInteger.class) {
                return n.toBigIntegerExact();
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(n.toPlainString() + " is not a valid " + raw.getSimpleName());
        }
        if (raw == double.class || raw == Double.class) {
            return n.doubleValue();
        }
        if (raw == float.class || raw == Float.class) {
            return n.floatValue();
        }
        return n;
    }

    // ---------------------------------------------------------------- results

    /** Converts a Java result (records, enums, collections…) to plain maps/lists/scalars. */
    static Object toPlain(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Character || value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Optional<?> o) {
            return toPlain(o.orElse(null));
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), toPlain(v)));
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            c.forEach(v -> out.add(toPlain(v)));
            return out;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                out.add(toPlain(Array.get(value, i)));
            }
            return out;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : value.getClass().getRecordComponents()) {
                try {
                    var accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    out.put(rc.getName(), toPlain(accessor.invoke(value)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot read record component " + rc.getName(), e);
                }
            }
            return out;
        }
        return value.toString();
    }

    // ---------------------------------------------------------------- helpers

    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        if (type instanceof GenericArrayType g) {
            return Array.newInstance(rawClass(g.getGenericComponentType()), 0).getClass();
        }
        return Object.class; // wildcards and type variables
    }

    private static Type typeArg(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            Type arg = p.getActualTypeArguments()[index];
            return arg instanceof java.lang.reflect.WildcardType w ? w.getUpperBounds()[0] : arg;
        }
        return Object.class;
    }

    private static Type componentType(Type type) {
        return type instanceof GenericArrayType g ? g.getGenericComponentType() : rawClass(type).getComponentType();
    }

    private static boolean isString(Class<?> c) {
        return c == String.class || c == CharSequence.class || c == char.class || c == Character.class;
    }

    private static boolean isInteger(Class<?> c) {
        return c == int.class || c == Integer.class || c == long.class || c == Long.class || c == short.class
                || c == Short.class || c == byte.class || c == Byte.class || c == BigInteger.class;
    }

    private static boolean isNumber(Class<?> c) {
        return c == double.class || c == Double.class || c == float.class || c == Float.class || c == BigDecimal.class;
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> l) {
            return l;
        }
        if (value instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        throw new IllegalArgumentException("expected an array but got " + describe(value));
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException("expected an object but got " + describe(value));
    }

    private static String describe(Object value) {
        return value instanceof Map ? "an object" : value instanceof Collection ? "an array"
                : value.getClass().getSimpleName().toLowerCase(Locale.ROOT) + " " + value;
    }
}
