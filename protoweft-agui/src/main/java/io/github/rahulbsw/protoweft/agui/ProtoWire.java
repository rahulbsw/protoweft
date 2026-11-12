package io.github.rahulbsw.protoweft.agui;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts between the JSON trees the SSE path uses and protobuf messages, field by field through
 * the descriptors: a field's JSON name is its {@code json_name}, {@code google.protobuf.Value} and
 * {@code Struct} carry open JSON, and enums travel by name. AG-UI's own reshaping (the envelope,
 * outcomes, message content) is {@link ProtoCodec}'s; this class knows nothing about events.
 *
 * <p>Loaded only when protobuf-java is present (see {@link ProtoBinding}).
 */
final class ProtoWire {

    /** Enum types whose JSON spelling is the lower-cased value name (JSON Patch's {@code op}). */
    private static final String PATCH_OP = "ag_ui.JsonPatchOperationType";
    private static final double MAX_SAFE_INTEGER = 9007199254740991d;
    private static final Map<Descriptor, Map<String, FieldDescriptor>> BY_JSON_NAME = new ConcurrentHashMap<>();

    private ProtoWire() {
    }

    // ---- JSON -> protobuf -------------------------------------------------------------------

    /** Sets {@code json}'s members on {@code builder}; members the message does not define are dropped. */
    static <B extends Message.Builder> B fill(B builder, Map<?, ?> json) {
        Map<String, FieldDescriptor> fields = fields(builder.getDescriptorForType());
        for (Map.Entry<?, ?> e : json.entrySet()) {
            FieldDescriptor f = fields.get(String.valueOf(e.getKey()));
            Object v = e.getValue();
            if (f == null || (v == null && !isValue(f))) {
                continue;
            }
            if (f.isRepeated()) {
                if (v instanceof List<?> list) {
                    for (Object item : list) {
                        builder.addRepeatedField(f, toField(builder, f, item));
                    }
                }
            } else {
                Object converted = toField(builder, f, v);
                if (converted != null) {
                    builder.setField(f, converted);
                }
            }
        }
        return builder;
    }

    private static Object toField(Message.Builder parent, FieldDescriptor f, Object v) {
        switch (f.getJavaType()) {
            case MESSAGE:
                String type = f.getMessageType().getFullName();
                if (type.equals("google.protobuf.Value")) {
                    return value(v);
                }
                if (type.equals("google.protobuf.Struct")) {
                    return v instanceof Map<?, ?> m ? struct(m) : null;
                }
                if (!(v instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException(f.getFullName() + " must be an object");
                }
                return fill(parent.newBuilderForField(f), m).build();
            case ENUM:
                EnumValueDescriptor ev = f.getEnumType().findValueByName(String.valueOf(v).toUpperCase(Locale.ROOT));
                if (ev == null) {
                    throw new IllegalArgumentException(f.getFullName() + " has no value '" + v + "'");
                }
                return ev;
            case STRING:
                return String.valueOf(v);
            case BOOLEAN:
                return v;
            case INT:
                return ((Number) v).intValue();
            case LONG:
                return ((Number) v).longValue();
            case FLOAT:
                return ((Number) v).floatValue();
            case DOUBLE:
                return ((Number) v).doubleValue();
            default:
                throw new IllegalArgumentException(f.getFullName() + ": unsupported field type " + f.getJavaType());
        }
    }

    /** Any JSON value; numbers become {@code double}, the only number the wire has. */
    static Value value(Object v) {
        if (v == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (v instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        }
        if (v instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        }
        if (v instanceof Number n) {
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        }
        if (v instanceof Map<?, ?> m) {
            return Value.newBuilder().setStructValue(struct(m)).build();
        }
        if (v instanceof List<?> l) {
            ListValue.Builder list = ListValue.newBuilder();
            l.forEach(item -> list.addValues(value(item)));
            return Value.newBuilder().setListValue(list).build();
        }
        throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
    }

    static Struct struct(Map<?, ?> m) {
        Struct.Builder s = Struct.newBuilder();
        m.forEach((k, item) -> s.putFields(String.valueOf(k), value(item)));
        return s.build();
    }

    // ---- protobuf -> JSON -------------------------------------------------------------------

    /**
     * The message as a JSON object. Fields with explicit presence appear only when set; implicit
     * ones (the schema's required members) always; repeated ones always, possibly empty.
     *
     * @throws AgUiFormatException for JSON the wire carried but JSON cannot hold (a non-finite
     *                             number, nesting deeper than {@link Json#MAX_DEPTH})
     */
    static Map<String, Object> json(Message m, int depth) {
        depth(depth);
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDescriptor f : m.getDescriptorForType().getFields()) {
            if (f.isRepeated()) {
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < m.getRepeatedFieldCount(f); i++) {
                    list.add(fromField(f, m.getRepeatedField(f, i), depth));
                }
                out.put(f.getJsonName(), list);
            } else if (!f.hasPresence() || m.hasField(f)) {
                out.put(f.getJsonName(), fromField(f, m.getField(f), depth));
            }
        }
        return out;
    }

    private static Object fromField(FieldDescriptor f, Object v, int depth) {
        switch (f.getJavaType()) {
            case MESSAGE:
                String type = f.getMessageType().getFullName();
                if (type.equals("google.protobuf.Value")) {
                    return json((Value) v, depth + 1);
                }
                if (type.equals("google.protobuf.Struct")) {
                    return json((Struct) v, depth + 1);
                }
                return json((Message) v, depth + 1);
            case ENUM:
                EnumValueDescriptor ev = (EnumValueDescriptor) v;
                if (ev.getIndex() < 0) {
                    // A value this build has no name for travels as its number, for the decoder to judge.
                    return String.valueOf(ev.getNumber());
                }
                return ev.getType().getFullName().equals(PATCH_OP) ? ev.getName().toLowerCase(Locale.ROOT) : ev.getName();
            default:
                return v;
        }
    }

    static Object json(Value v, int depth) {
        depth(depth);
        switch (v.getKindCase()) {
            case STRING_VALUE:
                return v.getStringValue();
            case BOOL_VALUE:
                return v.getBoolValue();
            case NUMBER_VALUE:
                return number(v.getNumberValue());
            case STRUCT_VALUE:
                return json(v.getStructValue(), depth + 1);
            case LIST_VALUE:
                List<Object> list = new ArrayList<>();
                for (Value item : v.getListValue().getValuesList()) {
                    list.add(json(item, depth + 1));
                }
                return list;
            default:
                // NULL_VALUE, and a Value with no kind set (which JSON can only read as null).
                return null;
        }
    }

    static Map<String, Object> json(Struct s, int depth) {
        depth(depth);
        Map<String, Object> out = new LinkedHashMap<>();
        // The object is the nesting level; its members sit at it (each JSON container counts once).
        s.getFieldsMap().forEach((k, item) -> out.put(k, json(item, depth)));
        return out;
    }

    /** The number as the JSON reader would have produced it: integers as integers, decimals as BigDecimal. */
    private static Object number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new AgUiFormatException("a number value is not finite");
        }
        if (d == Math.rint(d) && Math.abs(d) <= MAX_SAFE_INTEGER) {
            long l = (long) d;
            return l == (int) l ? (Object) (int) l : (Object) l;
        }
        return BigDecimal.valueOf(d);
    }

    private static void depth(int depth) {
        if (depth > Json.MAX_DEPTH) {
            throw new AgUiFormatException("nesting deeper than " + Json.MAX_DEPTH + " levels");
        }
    }

    private static boolean isValue(FieldDescriptor f) {
        return f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && f.getMessageType().getFullName().equals("google.protobuf.Value");
    }

    private static Map<String, FieldDescriptor> fields(Descriptor d) {
        return BY_JSON_NAME.computeIfAbsent(d, key -> {
            Map<String, FieldDescriptor> m = new HashMap<>();
            key.getFields().forEach(f -> m.put(f.getJsonName(), f));
            return m;
        });
    }
}
