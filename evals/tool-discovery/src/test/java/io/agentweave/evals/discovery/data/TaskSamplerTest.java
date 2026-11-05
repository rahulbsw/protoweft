package io.agentweave.evals.discovery.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSamplerTest {

    private static List<EvalTask> tasks() {
        List<EvalTask> out = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            out.add(new EvalTask("a" + i, "ds/a", "q", null, Map.of("t", 1), null, null));
        }
        for (int i = 0; i < 3; i++) {
            out.add(new EvalTask("b" + i, "ds/b", "q", null, Map.of("t", 1), null, null));
        }
        return out;
    }

    @Test
    void stratifiedAndDeterministic() {
        List<EvalTask> s = TaskSampler.sample(tasks(), 10, 42);
        assertThat(s).hasSize(10);
        assertThat(s.stream().filter(t -> t.dataset().equals("ds/b"))).hasSize(3);
        assertThat(TaskSampler.sample(tasks(), 10, 42)).isEqualTo(s);
        assertThat(TaskSampler.sample(tasks(), 10, 43)).isNotEqualTo(s);
    }

    @Test
    void zeroOrTooManyMeansAll() {
        assertThat(TaskSampler.sample(tasks(), 0, 1)).hasSize(33);
        assertThat(TaskSampler.sample(tasks(), 100, 1)).isEqualTo(tasks());
    }
}
