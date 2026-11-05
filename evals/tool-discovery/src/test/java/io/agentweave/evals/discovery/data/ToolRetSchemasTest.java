package io.agentweave.evals.discovery.data;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRetSchemasTest {

    private static Map<String, Object> schema(String docJson) {
        return ToolRetSchemas.toJsonSchema(Json.parseObject(docJson));
    }

    private static Map<String, Object> prop(Map<String, Object> schema, String name) {
        return Json.asMap(Json.asMap(schema.get("properties")).get(name));
    }

    @Test
    void jsonSchemaLikeWithDictTypeAndTopLevelRequired() {
        Map<String, Object> s = schema("""
                {"name":"x","parameters":{"type":"dict","properties":{"city":{"type":"string"},
                 "days":{"type":"int","default":"3"},"tags":{"type":"list"}}},"required":["city","nope"]}""");
        assertThat(s.get("type")).isEqualTo("object");
        assertThat(prop(s, "days")).containsEntry("type", "integer").doesNotContainKey("default");
        assertThat(prop(s, "tags")).containsEntry("type", "array").containsKey("items");
        assertThat(s.get("required")).isEqualTo(List.of("city"));
    }

    @Test
    void nestedSchemaKeepsOnlyKnownKeywordsAndValidRequired() {
        Map<String, Object> s = schema("""
                {"doc_arguments":{"type":"object","properties":{"opts":{"type":"object","x-weird":1,
                 "properties":{"deep":{"type":"str","required":true}}}},"required":["opts","ghost"]}}""");
        Map<String, Object> opts = prop(s, "opts");
        assertThat(opts).doesNotContainKey("x-weird");
        assertThat(opts.get("required")).isEqualTo(List.of("deep"));
        assertThat(prop(opts, "deep")).containsEntry("type", "string").doesNotContainKey("required");
        assertThat(s.get("required")).isEqualTo(List.of("opts"));
    }

    @Test
    void parameterMapWithOptionalSuffixEvenIfAParamIsNamedProperties() {
        Map<String, Object> s = schema("""
                {"parameters":{"ids":{"type":"str","description":"ZIPs","default":""},
                 "properties":{"type":"str, optional","default":"zip"}}}""");
        assertThat(prop(s, "ids")).containsEntry("type", "string").doesNotContainKey("default");
        assertThat(prop(s, "properties")).containsEntry("type", "string").containsEntry("default", "zip");
        assertThat(s.get("required")).isEqualTo(List.of("ids"));
    }

    @Test
    void toolAlpacaStringParameters() {
        Map<String, Object> s = schema("""
                {"parameters":{"countryCode":"Required. string. Two-letter code.","note":"Optional. integer. N."}}""");
        assertThat(prop(s, "countryCode")).containsEntry("description", "Two-letter code.");
        assertThat(prop(s, "note")).containsEntry("type", "integer");
        assertThat(s.get("required")).isEqualTo(List.of("countryCode"));
    }

    @Test
    void openApiStyleParameterList() {
        Map<String, Object> s = schema("""
                {"parameters":[{"name":"q","in":"query","required":"true","schema":{"type":"string"}},
                 {"name":"page","in":"optional","schema":{"type":"integer","default":1}},
                 {"name":"id","in":"path","schema":{"type":"String"}},
                 {"name":"img","in":"query","type":"image"}]}""");
        assertThat(prop(s, "page")).containsEntry("type", "integer").containsEntry("default", 1);
        assertThat(prop(s, "img")).containsEntry("type", "string");
        assertThat(s.get("required")).isEqualTo(List.of("q", "id"));
    }

    @Test
    void signatureStringRequiredOptionalListsInputsAndAppbench() {
        Map<String, Object> sig = schema("{\"parameters\":\"(image: str, k: List[int], t: float)\"}");
        assertThat(prop(sig, "k")).containsEntry("type", "array");
        assertThat(prop(sig, "t")).containsEntry("type", "number");
        Map<String, Object> tb = schema("""
                {"required_parameters":[{"name":"id","type":"NUMBER","default":5}],
                 "optional_parameters":[{"name":"v","type":"BOOLEAN","default":"true"}]}""");
        assertThat(prop(tb, "id")).containsEntry("type", "number").containsEntry("default", 5);
        assertThat(prop(tb, "v")).containsEntry("type", "boolean").doesNotContainKey("default");
        assertThat(tb.get("required")).isEqualTo(List.of("id"));
        Map<String, Object> gta = schema("{\"inputs\":[{\"name\":\"e\",\"type\":\"text\",\"optional\":true}]}");
        assertThat(gta).doesNotContainKey("required");
        Map<String, Object> app = schema("""
                {"additional_required_arguments":{"where_to (str)":"place","adults (int)":"n"},
                 "optional_arguments":{"rating (float)":"min"}}""");
        assertThat(prop(app, "adults")).containsEntry("type", "integer");
        assertThat(app.get("required")).isEqualTo(List.of("where_to", "adults"));
    }

    @Test
    void gorillaArgumentsAndEmptyDocs() {
        Map<String, Object> list = schema("{\"api_arguments\":[\"model_id\"]}");
        assertThat(prop(list, "model_id")).containsEntry("type", "string");
        Map<String, Object> map = schema("{\"api_arguments\":{\"pretrained\":\"True\"}}");
        assertThat(prop(map, "pretrained")).containsEntry("default", "True");
        Map<String, Object> none = schema("{\"name\":\"n\",\"description\":\"d\"}");
        assertThat(none).isEqualTo(Map.of("type", "object", "properties", Map.of()));
        assertThat(schema("{\"api_arguments\":\"N/A\"}")).containsEntry("type", "object");
    }

    @Test
    void propertyNamesAreMadeProviderSafe() {
        Map<String, Object> s = schema("""
                {"parameters":{"type":"object","properties":{"CFG Scale":{"type":"number"},"ok.name-1":{"type":"str"},
                 "!!!":{"type":"str"}},"required":["CFG Scale"]}}""");
        assertThat(Json.asMap(s.get("properties"))).containsOnlyKeys("CFG_Scale", "ok.name-1");
        assertThat(s.get("required")).isEqualTo(List.of("CFG_Scale"));
        Map<String, Object> gorilla = schema("""
                {"api_arguments":[{"name":"pretrained","type":"bool","default":false,"description":"weights"},
                 "model(x)[0].sum()"]}""");
        assertThat(prop(gorilla, "pretrained")).containsEntry("type", "boolean").containsEntry("default", false);
        assertThat(Json.asMap(gorilla.get("properties"))).containsKey("model_x_0_.sum");
        assertThat(ToolRetSchemas.safeKey("x".repeat(70))).hasSize(64);
    }

    @Test
    void specUsesNameAndFallbackDescriptions() {
        ToolSpec s = ToolRetSchemas.toSpec("toolink_tool_1", Json.parseObject("{\"func_description\":\"  adds  \"}"));
        assertThat(s.name()).isEqualTo("toolink_tool_1");
        assertThat(s.description()).isEqualTo("adds");
    }

    @Test
    void typeMapping() {
        assertThat(ToolRetSchemas.jsonType("str, optional")).isEqualTo("string");
        assertThat(ToolRetSchemas.jsonType("Dict[str, Any]")).isEqualTo("object");
        assertThat(ToolRetSchemas.jsonType("long")).isEqualTo("integer");
        assertThat(ToolRetSchemas.jsonType("double")).isEqualTo("number");
        assertThat(ToolRetSchemas.jsonType("string[]")).isEqualTo("array");
        assertThat(ToolRetSchemas.jsonType(null)).isEqualTo("string");
        assertThat(ToolRetSchemas.jsonType("NoneType")).isEqualTo("string");
    }
}
