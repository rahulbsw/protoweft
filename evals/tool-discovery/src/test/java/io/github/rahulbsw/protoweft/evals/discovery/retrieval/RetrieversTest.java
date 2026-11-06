package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.evals.discovery.Fixtures;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrieversTest {

    private static final EvalCatalog CATALOG = EvalCatalog.builder()
            .required(List.of(Fixtures.tool("g:a", "a"), Fixtures.tool("g:b", "b")))
            .distractorPool(Fixtures.distractors(50)).size(30).seed(1).build();
    private static final EvalTask TASK = new EvalTask("t", "test", "q", null, Map.of("g:b", 1, "g:a", 2), null, null);

    @Test
    void oraclePutsGoldFirstByGrade() {
        List<String> top = new OracleRetriever().retrieve(TASK, CATALOG, 5);
        assertThat(top).hasSize(5).startsWith("g:a", "g:b").doesNotHaveDuplicates();
        assertThat(new OracleRetriever().retrieve(TASK, CATALOG, 1)).containsExactly("g:a");
    }

    @Test
    void randomIsDeterministicPerTaskAndBounded() {
        RandomRetriever r = new RandomRetriever(9);
        assertThat(r.retrieve(TASK, CATALOG, 10)).hasSize(10).isEqualTo(r.retrieve(TASK, CATALOG, 10))
                .allMatch(CATALOG::contains);
        assertThat(r.retrieve(TASK, CATALOG, 100)).hasSize(30);
    }

    @Test
    void registry() {
        assertThat(Retrievers.create("oracle", Map.of()).name()).isEqualTo("oracle");
        assertThat(Retrievers.create("random", Map.of("seed", "3")).name()).isEqualTo("random");
        assertThatThrownBy(() -> Retrievers.create("bm25", Map.of())).hasMessageContaining("unknown retriever");
    }
}
