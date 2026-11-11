package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class FixturesTest {

    private static final TokenEstimator EST = TokenEstimator.o200k();

    @Test
    void normalizedKeyIgnoresObjectKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("q", "Paris");
        a.put("limit", 3);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("limit", 3);
        b.put("q", "Paris");
        assertThat(NormalizedArgs.key("geocode", a)).isEqualTo(NormalizedArgs.key("geocode", b));
        assertThat(NormalizedArgs.key("geocode", a)).isNotEqualTo(NormalizedArgs.key("reverse", a));
    }

    @Test
    void storeRecordLookupAndSaveLoadRoundTrip(@TempDir Path dir) {
        FixtureStore store = FixtureStore.empty();
        store.record(new ResultFixture("geocode", Map.of("q", "Paris"), "{\"lat\":48.85}", 7));
        assertThat(store.lookup("geocode", Map.of("q", "Paris"))).isPresent();
        assertThat(store.lookup("geocode", Map.of("q", "Berlin"))).isEmpty();

        Path file = dir.resolve("fx.jsonl");
        store.save(file);
        FixtureStore loaded = FixtureStore.load(file);
        assertThat(loaded.size()).isEqualTo(1);
        assertThat(loaded.lookup("geocode", Map.of("q", "Paris")))
                .get().extracting(ResultFixture::content).isEqualTo("{\"lat\":48.85}");
    }

    @Test
    void sizeDistributionSamplesObservedSizesAndFallsBack() {
        SizeDistribution empty = SizeDistribution.of(java.util.List.of(), 42);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.sample(new Random(1))).isEqualTo(42);

        SizeDistribution d = SizeDistribution.of(java.util.List.of(100, 100, 100), 42);
        assertThat(d.sample(new Random(1))).isEqualTo(100);
    }

    @Test
    void syntheticPayloadIsJsonSizedToTarget() {
        String payload = SyntheticPayload.of("geocode", 120, EST, new Random(7));
        assertThat(Json.parseObject(payload)).containsEntry("synthetic", true).containsEntry("tool", "geocode");
        assertThat(EST.text(payload)).isGreaterThanOrEqualTo(120);
    }

    @Test
    void providerReplaysFixtureOnHitAndSynthesizesSizedPayloadOnMiss() {
        FixtureStore store = FixtureStore.empty();
        store.record(new ResultFixture("geocode", Map.of("q", "Paris"), "RECORDED", 150));
        MockResultProvider provider = new MockResultProvider(store, EST, 64, 99);

        ToolResult hit = provider.result(ToolCall.of("c1", "geocode", Map.of("q", "Paris")));
        assertThat(hit.content()).isEqualTo("RECORDED");

        ToolCall miss = ToolCall.of("c2", "weather", Map.of("city", "Oslo"));
        ToolResult synthetic = provider.result(miss);
        assertThat(synthetic.content()).isNotEqualTo("RECORDED");
        // Miss is sized from the observed distribution (the one recorded fixture: 150 tokens).
        assertThat(EST.text(synthetic.content())).isGreaterThanOrEqualTo(150);
        // Deterministic: same call reproduces the same payload.
        assertThat(new MockResultProvider(store, EST, 64, 99).result(miss).content())
                .isEqualTo(synthetic.content());
    }
}
