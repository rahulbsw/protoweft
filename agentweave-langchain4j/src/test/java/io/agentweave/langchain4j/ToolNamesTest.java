package io.agentweave.langchain4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolNamesTest {

    @Test
    void safeNamesAreUnchanged() {
        assertThat(ToolNames.sanitize("get_weather-v2")).isEqualTo("get_weather-v2");
    }

    @Test
    void registrySeparatorBecomesDoubleUnderscore() {
        assertThat(ToolNames.sanitize("weather.forecast")).isEqualTo("weather__forecast");
        assertThat(ToolNames.sanitize("mcp.files/read file")).isEqualTo("mcp__files_read_file");
    }

    @Test
    void longNamesAreCutWithADistinguishingHash() {
        String a = "source." + "x".repeat(80) + "a";
        String b = "source." + "x".repeat(80) + "b";

        String sa = ToolNames.sanitize(a);
        String sb = ToolNames.sanitize(b);

        assertThat(sa).hasSize(64).matches(ToolNames.LLM_SAFE);
        assertThat(sb).hasSize(64).isNotEqualTo(sa);
        assertThat(ToolNames.sanitize(a)).isEqualTo(sa);
    }

    @Test
    void emptyNamesAreRejected() {
        assertThatThrownBy(() -> ToolNames.sanitize("")).isInstanceOf(IllegalArgumentException.class);
    }
}
