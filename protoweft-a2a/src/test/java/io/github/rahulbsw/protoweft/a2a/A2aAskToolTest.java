package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The remote agent as a {@link ToolConnector} with a single {@code ask} tool. */
class A2aAskToolTest {

    static TestServer server;
    static ToolConnector tools;

    @BeforeAll
    static void start() {
        server = new TestServer();
        tools = server.client().build();
        tools.start();
    }

    @AfterAll
    static void stop() {
        tools.close();
        server.close();
    }

    @Test
    void exposesExactlyOneAskTool() {
        List<ToolSpec> specs = tools.listTools();
        assertThat(specs).extracting(ToolSpec::name).containsExactly("ask");
        ToolSpec ask = specs.get(0);
        assertThat(ask.description()).contains("Test Agent", "Answers scripted test messages", "Echo", "Booking");
        assertThat(ask.inputSchema()).containsEntry("type", "object").containsEntry("required", List.of("message"));
        assertThat(map(ask.inputSchema().get("properties"))).containsOnlyKeys("message", "task_id", "context_id");
    }

    @Test
    void askReturnsTheReplyText() {
        ToolOutcome outcome = tools.invoke(ToolInvocation.of("ask", Map.of("message", "hello")));
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).isEqualTo("hi there");
        assertThat(outcome.structuredContent()).isEmpty();

        ToolOutcome task = tools.invoke(ToolInvocation.of("ask", Map.of("message", "report")));
        assertThat(task.text()).isEqualTo("done\nline1");
        assertThat(task.content()).hasSize(2); // the text, plus the data artifact kept as Json
        assertThat(map(task.structured())).containsEntry("state", "completed");
    }

    @Test
    void interruptedTaskCarriesTheQuestionAndIdsSoTheCallerCanAnswer() {
        ToolOutcome question = tools.invoke(ToolInvocation.of("ask", Map.of("message", "book a table")));
        assertThat(question.error()).isFalse();
        assertThat(question.text()).isEqualTo("For how many people?");
        Map<String, Object> ids = map(question.structured());
        assertThat(ids.get("state")).isEqualTo("input-required");
        assertThat(ids.get("task_id")).isInstanceOf(String.class);
        assertThat(ids.get("context_id")).isInstanceOf(String.class);

        ToolOutcome answer = tools.invoke(ToolInvocation.of("ask", Map.of("message", "6",
                "task_id", ids.get("task_id"), "context_id", ids.get("context_id"))));
        assertThat(answer.text()).isEqualTo("Booked for 6");
        assertThat(map(answer.structured()).get("task_id")).isEqualTo(ids.get("task_id"));
    }

    @Test
    void failedAndRejectedTasksAreErrorOutcomes() {
        ToolOutcome failed = tools.invoke(ToolInvocation.of("ask", Map.of("message", "boom")));
        assertThat(failed.error()).isTrue();
        assertThat(failed.text()).isEqualTo("kaboom");
        assertThat(tools.invoke(ToolInvocation.of("ask", Map.of("message", "reject"))).error()).isTrue();
    }

    @Test
    void badCallsAreExceptions() {
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("other", Map.of("message", "x"))))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("ask", Map.of())))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("ask", Map.of("message", "hi", "task_id", 5))))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
