package io.agentweave.evals.discovery.data;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalCatalogTest {

    private static final List<EvalTool> GOLD = List.of(Fixtures.tool("g:2", "search"), Fixtures.tool("g:1", "geocode"));

    private static EvalCatalog build(int size, long seed, List<EvalTool> pool) {
        return EvalCatalog.builder().required(GOLD).distractorPool(pool).size(size).seed(seed).build();
    }

    @Test
    void deterministicForSameSeedAndDifferentForOtherSeed() {
        List<EvalTool> pool = Fixtures.distractors(100);
        EvalCatalog a = build(20, 7, pool);
        EvalCatalog b = build(20, 7, pool);
        assertThat(a.tools()).isEqualTo(b.tools());
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.size()).isEqualTo(20);
        assertThat(build(20, 8, pool).fingerprint()).isNotEqualTo(a.fingerprint());
        // pool order does not matter
        List<EvalTool> reversed = new java.util.ArrayList<>(pool);
        java.util.Collections.reverse(reversed);
        assertThat(build(20, 7, reversed).fingerprint()).isEqualTo(a.fingerprint());
    }

    @Test
    void containsAllGoldAndSmallerCatalogsAreSubsets() {
        List<EvalTool> pool = Fixtures.distractors(300);
        EvalCatalog small = build(50, 42, pool);
        EvalCatalog large = build(200, 42, pool);
        assertThat(small.contains("g:1")).isTrue();
        assertThat(small.contains("g:2")).isTrue();
        assertThat(small.requiredIds()).containsExactlyInAnyOrder("g:1", "g:2");
        assertThat(large.tools()).containsAll(small.tools());
    }

    @Test
    void presentationOrderIsShuffled() {
        EvalCatalog c = build(50, 42, Fixtures.distractors(300));
        List<String> ids = c.tools().stream().map(EvalTool::id).toList();
        assertThat(ids.subList(0, 2)).isNotEqualTo(List.of("g:1", "g:2"));
    }

    @Test
    void namesAreProviderSafeReversibleAndGoldKeepsItsName() {
        EvalTool clash = new EvalTool("d:clash", "other", new ToolSpec("Search", "another search", Map.of()));
        EvalTool weird = new EvalTool("d:weird", "other", new ToolSpec("weird name!", "", Map.of()));
        EvalCatalog c = build(4, 1, List.of(clash, weird));
        assertThat(c.providerName("g:2")).isEqualTo("search");
        assertThat(c.providerName("d:clash")).isEqualTo("Search_2");
        assertThat(c.providerName("d:weird")).isEqualTo("weird_name");
        c.providerSpecs().forEach(s -> assertThat(s.name()).matches(ToolNames.SAFE));
        assertThat(c.toolIdForName("Search_2")).isEqualTo("d:clash");
        assertThat(c.providerSpec("d:clash").description()).isEqualTo("another search");
        assertThat(c.providerSpecs(List.of("d:weird", "missing", "g:1")).stream().map(ToolSpec::name))
                .containsExactly("weird_name", "geocode");
    }

    @Test
    void skipsContentDuplicatesOfIncludedTools() {
        EvalTool twin = new EvalTool("d:twin", "other", GOLD.get(0).spec());
        EvalCatalog c = build(3, 1, List.of(twin, Fixtures.tool("d:x", "x")));
        assertThat(c.contains("d:twin")).isFalse();
        assertThat(c.contains("d:x")).isTrue();
    }

    @Test
    void sizeErrors() {
        assertThatThrownBy(() -> build(1, 1, Fixtures.distractors(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("smaller than the 2 required");
        assertThatThrownBy(() -> build(10, 1, Fixtures.distractors(3)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pool too small");
        assertThat(build(0, 1, Fixtures.distractors(3)).size()).isEqualTo(5);
    }

    @Test
    void ofKeepsGivenOrder() {
        EvalCatalog c = EvalCatalog.of(Fixtures.distractors(3));
        assertThat(c.tools().stream().map(EvalTool::id)).containsExactly("d:0000", "d:0001", "d:0002");
    }
}
