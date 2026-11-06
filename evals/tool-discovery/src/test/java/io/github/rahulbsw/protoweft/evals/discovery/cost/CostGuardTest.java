package io.github.rahulbsw.protoweft.evals.discovery.cost;

import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelRequest;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ScriptedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CostGuardTest {

    private static final String MODEL = "fake:m";
    // $1 per Mtok in, $10 per Mtok out
    private static final PriceTable PRICES = PriceTable.empty().put(MODEL, new PriceTable.Price(1, 10, 0.1, 1.25));

    private static CostGuard guard(double budget, double runCap, SpendLedger ledger) {
        return new CostGuard(PRICES, budget, runCap, ledger, 1.0, 0, false);
    }

    @Test
    void zeroBudgetRefusesEveryCall() {
        CostGuard g = guard(0, 0, SpendLedger.inMemory());
        assertThatThrownBy(() -> g.beforeCall(MODEL, 1, 1)).isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("--budget-usd");
    }

    @Test
    void stopsBeforeTheCallThatCouldExceedTheBudget() {
        CostGuard g = guard(0.05, 0, SpendLedger.inMemory());
        // worst case: 10k in ($0.0125 at the cache-write rate) + 1k out ($0.01) = $0.0225
        g.beforeCall(MODEL, 10_000, 1_000);
        assertThat(g.afterCall(MODEL, new Usage(10_000, 1_000, 0, 0))).hasValueSatisfying(
                c -> assertThat(c).isCloseTo(0.02, within(1e-12)));
        g.beforeCall(MODEL, 10_000, 1_000); // 0.02 + 0.0225 = 0.0425 <= 0.05
        g.afterCall(MODEL, new Usage(10_000, 1_000, 0, 0));
        assertThat(g.runSpentUsd()).isCloseTo(0.04, within(1e-12));
        assertThatThrownBy(() -> g.beforeCall(MODEL, 10_000, 1_000)) // 0.04 + 0.0225 > 0.05
                .isInstanceOf(BudgetExceededException.class).hasMessageContaining("global budget");
    }

    @Test
    void perRunCap() {
        CostGuard g = guard(100, 0.01, SpendLedger.inMemory());
        assertThatThrownBy(() -> g.beforeCall(MODEL, 10_000, 1_000)).hasMessageContaining("per-run cap");
    }

    @Test
    void cachedTokensAreBilledAtTheCachedRate() {
        PriceTable.Price p = PRICES.price(MODEL).orElseThrow();
        // 1M input of which 600k cached reads and 100k cache writes; 0 output
        assertThat(p.cost(new Usage(1_000_000, 0, 600_000, 100_000))).isCloseTo(0.3 + 0.06 + 0.125, within(1e-12));
    }

    @Test
    void unpricedModelsNeedExplicitPermissionAndACallLimit() {
        CostGuard strict = guard(10, 0, SpendLedger.inMemory());
        assertThatThrownBy(() -> strict.beforeCall("fake:unpriced", 1, 1)).hasMessageContaining("no price");
        assertThatThrownBy(() -> new CostGuard(PRICES, 10, 0, SpendLedger.inMemory(), 1, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        CostGuard loose = new CostGuard(PRICES, 10, 0, SpendLedger.inMemory(), 1, 2, true);
        loose.beforeCall("fake:unpriced", 1, 1);
        assertThat(loose.afterCall("fake:unpriced", Usage.ZERO)).isEmpty();
        loose.beforeCall("fake:unpriced", 1, 1);
        loose.afterCall("fake:unpriced", Usage.ZERO);
        assertThatThrownBy(() -> loose.beforeCall("fake:unpriced", 1, 1)).hasMessageContaining("call limit");
    }

    @Test
    void ledgerCarriesSpendAcrossRuns(@TempDir Path dir) {
        Path file = dir.resolve("ledger.json");
        CostGuard first = guard(0.05, 0, SpendLedger.open(file));
        first.beforeCall(MODEL, 10_000, 1_000);
        first.afterCall(MODEL, new Usage(30_000, 1_000, 0, 0)); // $0.04
        assertThat(Files.exists(file)).isTrue();
        CostGuard second = guard(0.05, 0, SpendLedger.open(file));
        assertThat(second.ledger().totalUsd()).isCloseTo(0.04, within(1e-12));
        assertThatThrownBy(() -> second.beforeCall(MODEL, 10_000, 1_000)).isInstanceOf(BudgetExceededException.class);
    }

    @Test
    void guardedClientNeverReachesTheProviderWhenRefused() {
        ScriptedModelClient fake = new ScriptedModelClient("fake", "m",
                r -> ScriptedModelClient.text("ok", new Usage(100, 10, 0, 0)));
        GuardedModelClient c = new GuardedModelClient(fake, guard(0.0002, 0, SpendLedger.inMemory()),
                TokenEstimator.o200k());
        ModelRequest req = new ModelRequest(null, List.of(new Turn.User("hello")), List.of(), 10);
        c.send(req); // worst case ~$0.0001
        assertThatThrownBy(() -> c.send(req)).isInstanceOf(BudgetExceededException.class);
        assertThat(fake.requests()).hasSize(1);
    }

    @Test
    void priceFileAndFlags(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("prices.json");
        Files.writeString(f, "{\"_comment\": \"x\", \"a:m\": {\"input\": 3, \"output\": 15, \"cachedInput\": 0.3}}");
        PriceTable t = PriceTable.load(f).putFlag("b:n=1.5,6");
        assertThat(t.price("a:m").orElseThrow()).isEqualTo(new PriceTable.Price(3, 15, 0.3, 3));
        assertThat(t.price("b:n").orElseThrow()).isEqualTo(new PriceTable.Price(1.5, 6, 1.5, 1.5));
        Files.writeString(f, "{\"a:m\": {\"input\": null, \"output\": 1}}");
        assertThatThrownBy(() -> PriceTable.load(f)).hasMessageContaining("numeric");
    }
}
