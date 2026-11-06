package io.agentweave.evals.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliTest {

    @Test
    void parsesFlagsListsAndSwitches() {
        CliArgs a = new CliArgs(new String[] {"run-llm", "--arm", "all-tools", "--arm", "top-k:k=5,retriever=oracle",
                "--catalog-sizes", "50,all", "--dry-run", "--seed=7", "--temperature", "none"});
        assertThat(a.command()).isEqualTo("run-llm");
        assertThat(a.repeated("arm", List.of())).containsExactly("all-tools", "top-k:k=5,retriever=oracle");
        assertThat(a.integers("catalog-sizes", "1")).containsExactly(50, 0);
        assertThat(a.flag("dry-run")).isTrue();
        assertThat(a.longValue("seed", 1)).isEqualTo(7);
        assertThat(a.optionalDecimal("temperature")).isNull();
        assertThat(a.list("models", "x:y,z:w")).containsExactly("x:y", "z:w");
        a.rejectUnknown();
    }

    @Test
    void rejectsUnknownFlagsAndStrayArguments() {
        CliArgs a = new CliArgs(new String[] {"run-llm", "--typo", "1"});
        assertThatThrownBy(a::rejectUnknown).hasMessageContaining("--typo");
        assertThatThrownBy(() -> new CliArgs(new String[] {"run-llm", "stray"})).hasMessageContaining("stray");
    }

    @Test
    void paidRunsNeedAnExplicitBudgetBeforeAnyKeyIsRead() {
        assertThatThrownBy(() -> EvalMain.run(new String[] {"run-llm", "--data-dir", "/nonexistent"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--budget-usd is 0");
    }

    @Test
    void missingPriceIsRefused() {
        assertThatThrownBy(() -> EvalMain.run(new String[] {"run-llm", "--budget-usd", "1", "--models", "openai:x",
                "--data-dir", "/nonexistent"})).hasMessageContaining("no price for openai:x");
    }

    @Test
    void calibrateRejectsUnknownOptionsAndDocumentsTheNewCommands() {
        assertThatThrownBy(() -> EvalMain.run(new String[] {"calibrate", "--typo", "1", "--data-dir", "/nonexistent"}))
                .hasMessageContaining("--typo");
        assertThat(EvalMain.USAGE).contains("calibrate").contains("catalog-hybrid").contains("generic-search")
                .contains("native-anthropic").contains("native-openai");
    }

    @Test
    void listAndHelp() {
        assertThat(EvalMain.run(new String[] {"list"})).isZero();
        assertThat(EvalMain.run(new String[] {"help"})).isZero();
        assertThat(EvalMain.run(new String[] {"bogus"})).isEqualTo(2);
    }
}
