package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemasTest {

    private static final Map<String, Object> ORDER = Map.of(
            "type", "object",
            "description", "An order",
            "properties", Map.of(
                    "customer", Map.of(
                            "type", "object",
                            "description", "Who orders",
                            "properties", Map.of(
                                    "name", Map.of("type", "string", "description", "Full name"),
                                    "age", Map.of("type", "integer")),
                            "required", List.of("name")),
                    "items", Map.of(
                            "type", "array",
                            "description", "Line items",
                            "items", Map.of("type", "string")),
                    "priority", Map.of(
                            "type", "string",
                            "description", "How urgent",
                            "enum", List.of("LOW", "HIGH"))),
            "required", List.of("customer", "items"));

    @Test
    void coreMapToJsonObjectSchema() {
        JsonObjectSchema schema = JsonSchemas.toJsonObjectSchema(ORDER);

        assertThat(schema.description()).isEqualTo("An order");
        assertThat(schema.required()).containsExactlyInAnyOrder("customer", "items");
        JsonObjectSchema customer = (JsonObjectSchema) schema.properties().get("customer");
        assertThat(customer.description()).isEqualTo("Who orders");
        assertThat(customer.required()).containsExactly("name");
        assertThat(((JsonStringSchema) customer.properties().get("name")).description()).isEqualTo("Full name");
        assertThat(customer.properties().get("age")).isInstanceOf(JsonIntegerSchema.class);
        JsonArraySchema items = (JsonArraySchema) schema.properties().get("items");
        assertThat(items.description()).isEqualTo("Line items");
        assertThat(items.items()).isInstanceOf(JsonStringSchema.class);
        JsonEnumSchema priority = (JsonEnumSchema) schema.properties().get("priority");
        assertThat(priority.enumValues()).containsExactly("LOW", "HIGH");
        assertThat(priority.description()).isEqualTo("How urgent");
    }

    @Test
    void roundTripIsLossless() {
        assertThat(JsonSchemas.toMap(JsonSchemas.toJsonObjectSchema(ORDER))).isEqualTo(ORDER);
    }

    @Test
    void jsonObjectSchemaToMap() {
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .description("Search")
                .addStringProperty("query", "What to look for")
                .addProperty("tags", JsonArraySchema.builder().items(new JsonStringSchema()).build())
                .addEnumProperty("sort", List.of("asc", "desc"))
                .addProperty("page", JsonObjectSchema.builder().addIntegerProperty("size").required("size").build())
                .required("query")
                .build();

        assertThat(JsonSchemas.toMap(schema)).isEqualTo(Map.of(
                "type", "object",
                "description", "Search",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "What to look for"),
                        "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                        "sort", Map.of("type", "string", "enum", List.of("asc", "desc")),
                        "page", Map.of("type", "object",
                                "properties", Map.of("size", Map.of("type", "integer")),
                                "required", List.of("size"))),
                "required", List.of("query")));
        assertThat(JsonSchemas.toMap(null)).isEqualTo(Map.of("type", "object"));
    }

    @Test
    void keywordsWithoutATypedModelArePreservedAsRawSchema() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "email", Map.of("type", "string", "format", "email"),
                "count", Map.of("type", "integer", "minimum", 1)));

        JsonObjectSchema converted = JsonSchemas.toJsonObjectSchema(schema);

        assertThat(converted.properties().get("email")).isInstanceOf(JsonRawSchema.class);
        assertThat(JsonSchemas.toMap(converted)).isEqualTo(schema);
    }

    @Test
    void rootIsNormalized() {
        Map<String, Object> schema = Map.of(
                "$schema", "http://json-schema.org/draft-07/schema#",
                "title", "Args",
                "additionalProperties", Map.of("type", "string"),
                "properties", Map.of("address", Map.of("$ref", "#/definitions/Address")),
                "definitions", Map.of("Address", Map.of("type", "object",
                        "properties", Map.of("city", Map.of("type", "string")))));

        JsonObjectSchema converted = JsonSchemas.toJsonObjectSchema(schema);

        assertThat(converted.additionalProperties()).isTrue();
        assertThat(((JsonReferenceSchema) converted.properties().get("address")).reference()).isEqualTo("Address");
        assertThat(converted.definitions()).containsKey("Address");
        assertThat(JsonSchemas.toMap(converted)).doesNotContainKeys("$schema", "title", "definitions");
    }

    @Test
    void emptyAndUntypedSchemasAreObjects() {
        assertThat(JsonSchemas.toMap(JsonSchemas.toJsonObjectSchema(Map.of()))).isEqualTo(Map.of("type", "object"));
        assertThat(JsonSchemas.toMap(JsonSchemas.toJsonObjectSchema(null))).isEqualTo(Map.of("type", "object"));
        assertThat(JsonSchemas.toJsonObjectSchema(Map.of("properties", Map.of("x", Map.of("type", "boolean"))))
                .properties()).containsKey("x");
    }

    @Test
    void nonObjectRootIsRejected() {
        assertThatThrownBy(() -> JsonSchemas.toJsonObjectSchema(Map.of("type", "string")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");
    }
}
