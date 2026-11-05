package io.agentweave.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Jackson 2 (already on LangChain4j's classpath) for the JSON text LangChain4j exchanges. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private Json() {
    }

    /** Parses a JSON object; blank text is an empty object. */
    static Map<String, Object> parseObject(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> map = MAPPER.readValue(json, MAP);
        if (map == null) {
            throw new IllegalArgumentException("expected a JSON object but got null");
        }
        return map;
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value is not JSON-serializable: " + e.getOriginalMessage(), e);
        }
    }
}
