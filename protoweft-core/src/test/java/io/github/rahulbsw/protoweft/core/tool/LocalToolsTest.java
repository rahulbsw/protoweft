package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalToolsTest {

    enum Unit { CELSIUS, FAHRENHEIT }

    record Location(@ToolParam(description = "City name") String city, Optional<String> country) {
    }

    record Forecast(String city, double temperature, Unit unit) {
    }

    public static class Weather {
        @AgentTool(description = "Forecast for a location")
        public Forecast forecast(Location location, @ToolParam(description = "Unit") Unit unit, int days) {
            return new Forecast(location.city(), unit == Unit.CELSIUS ? 21.5 : 70.7, unit);
        }

        @AgentTool(name = "whoami", description = "Returns the caller")
        public String who(CallerIdentity caller) {
            return caller.name();
        }

        @AgentTool(description = "Sums numbers")
        public long sum(List<Integer> values, Optional<Integer> offset) {
            return values.stream().mapToLong(Integer::longValue).sum() + offset.orElse(0);
        }

        @AgentTool(description = "Always fails")
        public String broken() {
            throw new IllegalStateException("database down");
        }

        public String notATool() {
            return "x";
        }
    }

    private final LocalTools tools = LocalTools.builder().scan(new Weather()).build();

    @Test
    void scansAnnotatedMethodsOnly() {
        assertThat(tools.listTools()).extracting(ToolSpec::name)
                .containsExactly("broken", "forecast", "sum", "whoami");
    }

    @Test
    void generatesJsonSchemaFromParameterTypes() {
        ToolSpec forecast = tools.listTools().stream().filter(t -> t.name().equals("forecast")).findFirst().orElseThrow();
        Map<String, Object> schema = forecast.inputSchema();

        assertThat(schema).containsEntry("type", "object");
        assertThat(schema.get("required")).isEqualTo(List.of("location", "unit", "days"));
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertThat(props.get("days")).isEqualTo(Map.of("type", "integer"));
        assertThat(props.get("unit")).isEqualTo(Map.of("type", "string", "enum", List.of("CELSIUS", "FAHRENHEIT"),
                "description", "Unit"));
        Map<?, ?> location = (Map<?, ?>) props.get("location");
        assertThat(location.get("required")).isEqualTo(List.of("city"));
        assertThat(((Map<?, ?>) location.get("properties")).get("city"))
                .isEqualTo(Map.of("type", "string", "description", "City name"));
    }

    @Test
    void bindsArgumentsAndReturnsStructuredResult() {
        ToolOutcome outcome = tools.invoke(ToolInvocation.of("forecast",
                Map.of("location", Map.of("city", "Oslo"), "unit", "celsius", "days", 3.0)));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.structuredContent()).contains(Map.of("city", "Oslo", "temperature", 21.5, "unit", "CELSIUS"));
        assertThat(outcome.text()).isEqualTo("{\"city\":\"Oslo\",\"temperature\":21.5,\"unit\":\"CELSIUS\"}");
    }

    @Test
    void optionalArgumentsMayBeOmitted() {
        assertThat(tools.invoke(ToolInvocation.of("sum", Map.of("values", List.of(1, 2, 3)))).text()).isEqualTo("6");
        assertThat(tools.invoke(ToolInvocation.of("sum", Map.of("values", List.of(1), "offset", 10))).text()).isEqualTo("11");
    }

    @Test
    void injectsCallerIdentity() {
        assertThat(tools.invoke(ToolInvocation.of("whoami", Map.of()), CallerIdentity.of("alice")).text()).isEqualTo("alice");
    }

    @Test
    void toolExceptionsBecomeFailureOutcomes() {
        ToolOutcome outcome = tools.invoke(ToolInvocation.of("broken", Map.of()));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.text()).isEqualTo("database down");
    }

    @Test
    void badArgumentsAreInvalidArguments() {
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("sum", Map.of("values", List.of(1.5)))))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("forecast", Map.of("unit", "KELVIN"))))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void unknownToolIsToolNotFound() {
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("nope", Map.of())))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));
    }

    @Test
    void rejectsUnsupportedParameterTypesAtScanTime() {
        class Bad {
            @AgentTool(description = "x")
            public void bad(Thread t) {
            }
        }
        assertThatThrownBy(() -> LocalTools.builder().scan(new Bad()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported tool parameter type");
    }

    @Test
    void programmaticToolsAndDuplicateNames() {
        LocalTools.Builder builder = LocalTools.builder()
                .tool("ping", "Health check", Map.of("type", "object"), (args, caller) -> ToolOutcome.text("pong"));
        assertThat(builder.build().invoke(ToolInvocation.of("ping", Map.of())).text()).isEqualTo("pong");
        assertThatThrownBy(() -> builder.tool("ping", "again", Map.of(), (a, c) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.tool("has.dot", "x", Map.of(), (a, c) -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    public static class Slow {
        @AgentTool(description = "Reports progress")
        public String work(int steps, ToolProgress progress) {
            for (int i = 1; i <= steps; i++) {
                progress.progress(i, (double) steps, "step " + i);
            }
            progress.info("done");
            return "ok";
        }
    }

    @Test
    void injectsToolProgressWithoutPuttingItInTheSchema() {
        LocalTools slow = LocalTools.builder().scan(new Slow()).build();
        assertThat(String.valueOf(((Map<?, ?>) slow.listTools().get(0).inputSchema().get("properties")).keySet())).isEqualTo("[steps]");

        List<String> events = new java.util.ArrayList<>();
        ToolProgress recorder = new ToolProgress() {
            public void progress(double done, Double total, String message) {
                events.add(done + "/" + total + " " + message);
            }

            public void log(Level level, String message) {
                events.add(level + " " + message);
            }
        };
        ToolRegistry registry = new ToolRegistry().register("s", slow);
        assertThat(registry.invoke(ToolInvocation.of("s.work", Map.of("steps", 2)), CallerIdentity.ANONYMOUS, recorder)
                .text()).isEqualTo("ok");
        assertThat(events).containsExactly("1.0/2.0 step 1", "2.0/2.0 step 2", "INFO done");
        assertThat(slow.invoke(ToolInvocation.of("work", Map.of("steps", 1))).text()).as("NOOP by default").isEqualTo("ok");
    }

    @Test
    void programmaticProgressAwareHandlers() {
        List<String> logs = new java.util.ArrayList<>();
        LocalTools tools = LocalTools.builder().tool("p", "progress", Map.of("type", "object"),
                (ProgressAwareToolHandler) (args, caller, progress) -> {
                    progress.info("hi " + caller.name());
                    return ToolOutcome.text("ok");
                }).build();
        tools.invoke(ToolInvocation.of("p", Map.of()), CallerIdentity.of("ann"), new ToolProgress() {
            public void progress(double d, Double t, String m) {
            }

            public void log(Level level, String message) {
                logs.add(message);
            }
        });
        assertThat(logs).containsExactly("hi ann");
    }
}
