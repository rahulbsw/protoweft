package io.github.rahulbsw.protoweft.evals.discovery;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON helpers on Jackson 3. Values are plain maps/lists/scalars, as in {@code ToolSpec}. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private Json() {
    }

    /** Parses any JSON value into plain maps/lists/scalars. */
    public static Object parse(String json) {
        return MAPPER.readValue(json, Object.class);
    }

    /** Parses a JSON object. */
    public static Map<String, Object> parseObject(String json) {
        return MAPPER.readValue(json, MAP);
    }

    public static Object read(Path file) {
        return MAPPER.readValue(file.toFile(), Object.class);
    }

    /** Compact single-line JSON, suitable for JSONL. */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static String writePretty(Object value) {
        return PRETTY.writeValueAsString(value);
    }

    /** Reads every non-blank line of a JSONL file as a JSON object. */
    public static List<Map<String, Object>> readJsonl(Path file) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(parseObject(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return rows;
    }

    /** Converts a value (for example a record or SDK-independent map) to plain maps/lists. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
