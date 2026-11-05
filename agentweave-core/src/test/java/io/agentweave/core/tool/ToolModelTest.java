package io.agentweave.core.tool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolModelTest {

    @Test
    void invocationAllowsNullArgumentValuesAndIsImmutable() {
        Map<String, Object> args = new HashMap<>();
        args.put("optional", null);
        ToolInvocation invocation = ToolInvocation.of("t", args);
        args.put("later", 1);

        assertThat(invocation.arguments()).containsOnlyKeys("optional");
    }

    @Test
    void outcomeTextJoinsOnlyTextParts() {
        ToolOutcome outcome = new ToolOutcome(List.of(
                new Content.Text("a"), new Content.Data("image/png", "AAAA"),
                new Content.Unmapped("resource_link"), new Content.Text("b")), false, null);

        assertThat(outcome.text()).isEqualTo("a\nb");
        assertThat(outcome.structuredContent()).isEmpty();
    }
}
