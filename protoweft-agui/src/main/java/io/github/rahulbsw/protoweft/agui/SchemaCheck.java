package io.github.rahulbsw.protoweft.agui;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A structural check of tool arguments against a JSON Schema: the root must be an object, required
 * members present, and declared members of the declared {@code type} and within {@code enum}. It is
 * not a full validator (no nested schemas, formats or combinators); the tool's own validation still
 * applies. Arguments are model output, so this runs before anything else sees them.
 */
final class SchemaCheck {

    private SchemaCheck() {
    }

    /** The first problem found, or {@code null}. */
    static String problem(Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (name instanceof String s && !args.containsKey(s)) {
                    return "'" + s + "' is required";
                }
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<String, Object> arg : args.entrySet()) {
                if (properties.get(arg.getKey()) instanceof Map<?, ?> property) {
                    if (property.get("type") instanceof String type && !matches(type, arg.getValue())) {
                        return "'" + arg.getKey() + "' must be of type " + type;
                    }
                    if (property.get("enum") instanceof List<?> values
                            && values.stream().noneMatch(v -> JsonPatch.equal(v, arg.getValue()))) {
                        return "'" + arg.getKey() + "' must be one of " + values;
                    }
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    return "'" + arg.getKey() + "' is not a parameter of this tool";
                }
            }
        }
        return null;
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "null" -> value == null;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && isInteger(n);
            default -> true;
        };
    }

    private static boolean isInteger(Number n) {
        try {
            BigDecimal d = new BigDecimal(n.toString());
            return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
