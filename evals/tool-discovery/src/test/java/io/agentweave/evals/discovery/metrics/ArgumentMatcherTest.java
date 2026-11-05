package io.agentweave.evals.discovery.metrics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArgumentMatcherTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("name", Map.of("type", "string"), "type", Map.of("type", "string"),
                    "description", Map.of("type", "string"), "limit", Map.of("type", "integer")),
            "required", List.of("name", "type"));

    @Test
    void normalizedValuesMatch() {
        Map<String, Object> gold = Map.of("name", "E-Book Reader", "type", "DIGITAL");
        Map<String, Object> pred = Map.of("name", "  e-book   reader ", "type", "digital");
        assertThat(ArgumentMatcher.match(gold, pred, SCHEMA).matched()).isTrue();
    }

    @Test
    void numbersBooleansAndNumericStrings() {
        assertThat(ArgumentMatcher.valuesEqual(5, 5.0)).isTrue();
        assertThat(ArgumentMatcher.valuesEqual(25, "25")).isTrue();
        assertThat(ArgumentMatcher.valuesEqual(51.5074, "51.50740")).isTrue();
        assertThat(ArgumentMatcher.valuesEqual(5, 6)).isFalse();
        assertThat(ArgumentMatcher.valuesEqual(true, "TRUE")).isTrue();
        assertThat(ArgumentMatcher.valuesEqual(false, "yes")).isFalse();
        assertThat(ArgumentMatcher.valuesEqual("ｆｕｌｌｗｉｄｔｈ", "fullwidth")).isTrue(); // NFKC
    }

    @Test
    void listsAreOrderedAndObjectsAllowExtraKeys() {
        assertThat(ArgumentMatcher.valuesEqual(List.of("a", "b"), List.of("A", "b"))).isTrue();
        assertThat(ArgumentMatcher.valuesEqual(List.of("a", "b"), List.of("b", "a"))).isFalse();
        assertThat(ArgumentMatcher.valuesEqual(List.of("a"), "a")).isFalse();
        assertThat(ArgumentMatcher.valuesEqual(Map.of("k", 1), Map.of("k", "1", "extra", 2))).isTrue();
        assertThat(ArgumentMatcher.valuesEqual(Map.of("k", 1), Map.of("other", 1))).isFalse();
        assertThat(ArgumentMatcher.valuesEqual("a", Map.of())).isFalse();
    }

    @Test
    void missingGoldKeyOrDifferentValueFails() {
        Map<String, Object> gold = Map.of("name", "X", "type", "DIGITAL", "description", "Read books.");
        ArgumentMatcher.Result r = ArgumentMatcher.match(gold, Map.of("name", "X", "type", "DIGITAL"), SCHEMA);
        assertThat(r.matched()).isFalse();
        assertThat(r.reasons()).containsExactly("missing description");
        ArgumentMatcher.Result paraphrase = ArgumentMatcher.match(gold,
                Map.of("name", "X", "type", "DIGITAL", "description", "Read many books."), SCHEMA);
        assertThat(paraphrase.matched()).isFalse();
        assertThat(paraphrase.reasons()).containsExactly("value differs for description");
        // The lenient companion ignores values.
        assertThat(ArgumentMatcher.keysMatch(gold, Map.of("name", "X", "type", "D", "description", "?"), SCHEMA)
                .matched()).isTrue();
    }

    @Test
    void requiredAndUndeclaredKeys() {
        Map<String, Object> gold = Map.of("name", "X");
        assertThat(ArgumentMatcher.match(gold, Map.of("name", "X"), SCHEMA).reasons())
                .containsExactly("missing required type");
        // Extra declared optional parameter: allowed, value not compared.
        assertThat(ArgumentMatcher.match(gold, Map.of("name", "X", "type", "SERVICE", "limit", 3), SCHEMA).matched())
                .isTrue();
        assertThat(ArgumentMatcher.match(gold, Map.of("name", "X", "type", "SERVICE", "color", "red"), SCHEMA).reasons())
                .containsExactly("undeclared argument color");
        // A schema without declared properties is open.
        assertThat(ArgumentMatcher.match(gold, Map.of("name", "X", "color", "red"), Map.of("type", "object")).matched())
                .isTrue();
    }

    @Test
    void goldNullMeansAbsentOrNull() {
        Map<String, Object> gold = new HashMap<>();
        gold.put("name", "X");
        gold.put("description", null);
        assertThat(ArgumentMatcher.match(gold, Map.of("name", "X", "type", "T"), SCHEMA).matched()).isTrue();
        assertThat(ArgumentMatcher.match(gold, Map.of("name", "X", "type", "T", "description", "d"), SCHEMA).matched())
                .isFalse();
    }
}
