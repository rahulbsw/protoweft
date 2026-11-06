package io.github.rahulbsw.protoweft.core.observe;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationHandlerTest {

    private final Observation obs = new Observation(ProtocolId.MCP, "tools/call", "echo");

    @Test
    void reportsStartAndSuccess() {
        List<String> events = new ArrayList<>();
        ObservationHandler handler = recording(events);

        String result = ObservationHandler.observe(handler, obs, () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(events).containsExactly("start:tools/call", "success:tools/call");
    }

    @Test
    void reportsErrorAndRethrows() {
        List<String> events = new ArrayList<>();

        assertThatThrownBy(() -> ObservationHandler.observe(recording(events), obs, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(events).containsExactly("start:tools/call", "error:boom");
    }

    @Test
    void handlerFailuresNeverBreakTheCall() {
        ObservationHandler broken = new ObservationHandler() {
            @Override
            public void onStart(Observation o) {
                throw new RuntimeException("telemetry down");
            }
        };
        assertThat(ObservationHandler.observe(broken, obs, () -> 42)).isEqualTo(42);
    }

    private static ObservationHandler recording(List<String> events) {
        return new ObservationHandler() {
            @Override
            public void onStart(Observation o) {
                events.add("start:" + o.operation());
            }

            @Override
            public void onSuccess(Observation o, Duration elapsed) {
                events.add("success:" + o.operation());
            }

            @Override
            public void onError(Observation o, Duration elapsed, Throwable error) {
                events.add("error:" + error.getMessage());
            }
        };
    }
}
