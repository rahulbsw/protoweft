package io.github.rahulbsw.protoweft.evals.discovery.llm;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBreakdownTest {

    private static final TokenEstimator EST = TokenEstimator.o200k();
    private static final ToolSpec TOOL = new ToolSpec("geocode", "Resolve a place name to coordinates",
            Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))));

    @Test
    void plusAndTotalSumSegmentwise() {
        TokenBreakdown a = new TokenBreakdown(1, 2, 3, 4);
        TokenBreakdown b = new TokenBreakdown(10, 20, 30, 40);
        assertThat(a.total()).isEqualTo(10);
        assertThat(a.plus(b)).isEqualTo(new TokenBreakdown(11, 22, 33, 44));
        assertThat(TokenBreakdown.ZERO.plus(a)).isEqualTo(a);
    }

    @Test
    void breakdownSplitsSystemDefinitionsResultsHistory() {
        List<Turn> conversation = List.of(
                new Turn.User("Where is Paris, the capital of France?"),
                new Turn.Assistant("", List.of(ToolCall.of("c1", "geocode", Map.of("q", "Paris"))), null),
                new Turn.ToolResults(List.of(new ToolResult("c1", "{\"lat\":48.85,\"lon\":2.35,\"name\":\"Paris\"}", false))));
        ModelRequest req = new ModelRequest("You are a careful tool-using assistant.", conversation,
                List.of(TOOL), 256);

        TokenBreakdown b = EST.breakdown(req);

        assertThat(b.system()).isEqualTo(EST.text("You are a careful tool-using assistant."));
        assertThat(b.definitions()).isEqualTo(EST.tools(List.of(TOOL))).isPositive();
        assertThat(b.results()).isPositive();
        assertThat(b.history()).isPositive();
        // request() is the sum of the breakdown, unchanged behaviour.
        assertThat(EST.request(req)).isEqualTo(b.total());
    }

    @Test
    void emptyRequestSegmentsAreZeroExceptDefinitions() {
        ModelRequest req = new ModelRequest(null, List.of(new Turn.User("")), List.of(TOOL), 64);
        TokenBreakdown b = EST.breakdown(req);
        assertThat(b.system()).isZero();
        assertThat(b.results()).isZero();
        assertThat(b.history()).isZero();
        assertThat(b.definitions()).isPositive();
    }
}
