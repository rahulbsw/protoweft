package io.github.rahulbsw.protoweft.evals.discovery.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolNamesTest {

    @Test
    void sanitizesToProviderSafeNames() {
        assertThat(ToolNames.sanitize("Order Status")).isEqualTo("Order_Status");
        assertThat(ToolNames.sanitize("tavily-search")).isEqualTo("tavily-search");
        assertThat(ToolNames.sanitize("café.lookup/v2")).isEqualTo("cafe_lookup_v2");
        assertThat(ToolNames.sanitize("  ..  ")).isEqualTo("tool");
        assertThat(ToolNames.sanitize("天気")).isEqualTo("tool");
        assertThat(ToolNames.sanitize("x".repeat(100))).hasSize(64);
        assertThat(ToolNames.sanitize("a b c")).matches(ToolNames.SAFE);
    }

    @Test
    void dedupesCaseInsensitivelyAndIsReversible() {
        ToolNames names = new ToolNames();
        assertThat(names.assign("id1", "search")).isEqualTo("search");
        assertThat(names.assign("id2", "Search")).isEqualTo("Search_2");
        assertThat(names.assign("id3", "search!")).isEqualTo("search_3");
        assertThat(names.assign("id1", "ignored")).isEqualTo("search");
        assertThat(names.idOf("Search_2")).isEqualTo("id2");
        assertThat(names.nameOf("id3")).isEqualTo("search_3");
        assertThat(names.idOf("nope")).isNull();
    }

    @Test
    void suffixFitsWithin64Characters() {
        ToolNames names = new ToolNames();
        String longName = "y".repeat(70);
        names.assign("a", longName);
        String second = names.assign("b", longName);
        assertThat(second).hasSize(64).endsWith("_2").matches(ToolNames.SAFE);
    }
}
