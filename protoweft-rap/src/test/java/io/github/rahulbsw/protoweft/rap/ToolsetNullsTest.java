package io.github.rahulbsw.protoweft.rap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for the fuzzing finding: JSON nulls in a tool's schema or annotations are dropped, not fatal. */
class ToolsetNullsTest {

    @Test
    void nullValuedKeywordsAreDroppedAtEveryDepth() {
        Toolset toolset = Toolset.parse("https://tools.example.com", """
                {"name":"weather","endpoint":"https://tools.example.com/invoke","tools":[
                  {"name":"get_forecast","description":"Forecast",
                   "inputSchema":{"type":"object","default":null,
                                  "properties":{"city":{"type":"string","default":null}},"enum":[1,null]},
                   "annotations":{"x-protoweft-title":null,"custom":{"k":null}}}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Map<String, Object> schema = toolset.tools().get("get_forecast").inputSchema();
        assertThat(schema).containsOnlyKeys("type", "properties", "enum");
        assertThat(schema.get("properties")).isEqualTo(Map.of("city", Map.of("type", "string")));
        List<?> values = (List<?>) schema.get("enum");
        assertThat(values).hasSize(2);
        assertThat(values.get(1)).isNull();   // nulls inside arrays are values, not keywords
        assertThat(Toolset.annotationsFor(toolset.tools().get("get_forecast"))).isEqualTo(Map.of("custom", Map.of()));
    }
}
