package io.github.rahulbsw.protoweft.evals.discovery.shaping;

import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShapingTest {

    private static final TokenEstimator EST = TokenEstimator.o200k();

    private static String big() {
        return "{\"rows\":[" + "\"" + "lorem ipsum dolor ".repeat(200) + "\"" + "],\"count\":2,\"ok\":true}";
    }

    @Test
    void rawPassesThrough() {
        ToolResult r = new ToolResult("c1", big(), false);
        assertThat(ResultPolicy.raw().shape(r, EST).content()).isEqualTo(big());
    }

    @Test
    void truncateCapsTokensAndMarks() {
        ToolResult shaped = ResultPolicy.truncate(50).shape(new ToolResult("c1", big(), false), EST);
        assertThat(EST.text(shaped.content())).isLessThan((long) (50 * 1.5 + 30));
        assertThat(shaped.content()).contains("truncated").contains("call again");
    }

    @Test
    void projectKeepsScalarsAndElidesBulk() {
        ToolResult shaped = ResultPolicy.project(40).shape(new ToolResult("c1", big(), false), EST);
        Map<String, Object> out = Json.parseObject(shaped.content());
        assertThat(out).containsEntry("count", 2).containsEntry("ok", true);
        assertThat(Json.asMap(out.get("rows"))).containsKey("_elided");
        assertThat(EST.text(shaped.content())).isLessThan(EST.text(big()));
    }

    @Test
    void byReferenceReplacesWithHandleAndSummary() {
        ToolResult shaped = ResultPolicy.byReference(40).shape(new ToolResult("c1", big(), false), EST);
        Map<String, Object> out = Json.parseObject(shaped.content());
        assertThat(String.valueOf(out.get("ref"))).startsWith("res://c1");
        assertThat(out).containsKey("summary").containsKey("tokens");
        assertThat(EST.text(shaped.content())).isLessThan(EST.text(big()));
    }

    @Test
    void clearStaleStubsAllButMostRecentToolResults() {
        List<Turn> conv = List.of(
                new Turn.User("go"),
                new Turn.Assistant("", List.of(), null),
                new Turn.ToolResults(List.of(new ToolResult("c1", "FIRST-RESULT-PAYLOAD", false))),
                new Turn.Assistant("", List.of(), null),
                new Turn.ToolResults(List.of(new ToolResult("c2", "SECOND-RESULT-PAYLOAD", false))));
        List<Turn> out = HistoryPolicy.clearStale().apply(conv);
        assertThat(((Turn.ToolResults) out.get(2)).results().get(0).content()).isEqualTo("[result cleared]");
        assertThat(((Turn.ToolResults) out.get(4)).results().get(0).content()).isEqualTo("SECOND-RESULT-PAYLOAD");
        assertThat(HistoryPolicy.full().apply(conv)).isSameAs(conv);
    }

    @Test
    void parseBuildsNamedPolicies() {
        assertThat(ResultPolicy.parse("truncate=128").name()).isEqualTo("truncate=128");
        assertThat(ResultPolicy.parse("byref").name()).isEqualTo("byref");
        assertThat(HistoryPolicy.parse("clear-stale").name()).isEqualTo("clear-stale");
    }
}
