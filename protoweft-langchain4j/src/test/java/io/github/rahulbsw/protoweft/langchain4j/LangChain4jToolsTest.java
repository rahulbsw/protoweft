package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jToolsTest {

    public static class Calculator {
        @Tool("Adds two integers")
        public int add(@P("first") int a, @P("second") int b) {
            return a + b;
        }

        @Tool(name = "divide", value = "Divides")
        public double div(double a, double b) {
            if (b == 0) {
                throw new ArithmeticException("division by zero");
            }
            return a / b;
        }

        @Tool("Says who is calling")
        public String whoami(InvocationParameters params) {
            CallerIdentity caller = params.get(LangChain4jTools.CALLER_PARAMETER);
            return caller.name();
        }

        @Tool("Upper-cases words")
        public List<String> shout(List<String> words, Mode mode) {
            return words.stream().map(w -> mode == Mode.LOUD ? w.toUpperCase() : w).toList();
        }

        public enum Mode { LOUD, QUIET }
    }

    public static class BadName {
        @Tool(name = "has.dot", value = "x")
        public String x() {
            return "x";
        }
    }

    private final LocalTools tools = LocalTools.builder().addAll(LangChain4jTools.from(new Calculator())).build();

    @Test
    void toolSpecsAreConverted() {
        assertThat(tools.listTools()).extracting(ToolSpec::name).containsExactly("add", "divide", "shout", "whoami");
        ToolSpec add = tools.listTools().get(0);
        assertThat(add.description()).isEqualTo("Adds two integers");
        assertThat(add.inputSchema()).isEqualTo(Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer", "description", "first"),
                        "b", Map.of("type", "integer", "description", "second")),
                "required", List.of("a", "b")));
        @SuppressWarnings("unchecked")
        Map<String, Object> shoutProps = (Map<String, Object>) tools.listTools().get(2).inputSchema().get("properties");
        assertThat(shoutProps.get("mode")).isEqualTo(Map.of("type", "string", "enum", List.of("LOUD", "QUIET")));
        assertThat(shoutProps.get("words")).isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
    }

    @Test
    void invocationRunsThroughLocalTools() {
        ToolOutcome sum = tools.invoke(ToolInvocation.of("add", Map.of("a", 2, "b", 40)));
        assertThat(sum.error()).isFalse();
        assertThat(sum.text()).isEqualTo("42");

        ToolOutcome shout = tools.invoke(ToolInvocation.of("shout", Map.of("words", List.of("hi", "you"), "mode", "LOUD")));
        assertThat(shout.text()).isEqualTo("[\"HI\",\"YOU\"]");
    }

    @Test
    void methodExceptionsBecomeFailureOutcomes() {
        ToolOutcome outcome = tools.invoke(ToolInvocation.of("divide", Map.of("a", 1, "b", 0)));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.text()).isEqualTo("division by zero");
    }

    @Test
    void unbindableArgumentsAreInvalidArguments() {
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("add", Map.of("a", "not a number", "b", 1))))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS))
                .hasMessageContaining("add");
    }

    @Test
    void callerIsPassedAsInvocationParameter() {
        ToolOutcome outcome = tools.invoke(ToolInvocation.of("whoami", Map.of()), CallerIdentity.of("bob"));

        assertThat(outcome.text()).isEqualTo("bob");
    }

    @Test
    void invalidLocalToolNamesAreRejected() {
        assertThatThrownBy(() -> LangChain4jTools.from(new BadName()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has.dot");
        assertThatThrownBy(() -> LangChain4jTools.from(new Calculator(), new Calculator()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }
}
