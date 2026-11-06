package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiToolsTest {

    public static class MathTools {
        @Tool(description = "Adds two integers")
        public int add(@ToolParam(description = "first") int a, @ToolParam(description = "second") int b) {
            return a + b;
        }

        @Tool(description = "Says who is calling")
        public String whoami(ToolContext context) {
            ToolProgress progress = (ToolProgress) context.getContext().get(SpringAiTools.PROGRESS_KEY);
            progress.info("looking up caller");
            return ((CallerIdentity) context.getContext().get(SpringAiTools.CALLER_KEY)).name();
        }

        @Tool(description = "Weather forecast for a city")
        public Map<String, Object> forecast(String city) {
            return Map.of("city", city, "tempC", 21);
        }

        @Tool(description = "Always fails")
        public String explode() {
            throw new IllegalStateException("boom");
        }

        @Tool(description = "Throws an Protoweft exception")
        public String unreachable() {
            throw new ProtoweftException(ErrorCategory.CONNECTION, null, "upstream down");
        }
    }

    public static class BadName {
        @Tool(name = "bad.name", description = "Has a dot")
        public String bad() {
            return "x";
        }
    }

    private static LocalTools localTools() {
        return LocalTools.builder()
                .addAll(SpringAiTools.from(MethodToolCallbackProvider.builder().toolObjects(new MathTools()).build()))
                .build();
    }

    @Test
    void toolMethodsBecomeLocalToolsWithSchemas() {
        List<ToolSpec> specs = localTools().listTools();

        assertThat(specs).extracting(ToolSpec::name)
                .containsExactly("add", "explode", "forecast", "unreachable", "whoami");
        ToolSpec add = specs.get(0);
        assertThat(add.description()).isEqualTo("Adds two integers");
        assertThat(add.inputSchema()).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) add.inputSchema().get("properties");
        assertThat(properties).containsKeys("a", "b");
        List<Object> required = List.copyOf((List<?>) add.inputSchema().get("required"));
        assertThat(required).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void invokeRoundTripsArgumentsResultsCallerAndProgress() {
        List<String> logs = new CopyOnWriteArrayList<>();
        ToolProgress progress = new ToolProgress() {
            @Override
            public void progress(double done, Double total, String message) {
            }

            @Override
            public void log(Level level, String message) {
                logs.add(level + " " + message);
            }
        };
        LocalTools tools = localTools();

        assertThat(tools.invoke(ToolInvocation.of("add", Map.of("a", 2, "b", 3))).text()).isEqualTo("5");
        assertThat(tools.invoke(ToolInvocation.of("whoami", Map.of()), CallerIdentity.of("carol"), progress).text())
                .isEqualTo("carol");
        ToolOutcome forecast = tools.invoke(ToolInvocation.of("forecast", Map.of("city", "Oslo")));

        assertThat(logs).containsExactly("INFO looking up caller");
        assertThat(forecast.error()).isFalse();
        assertThat(forecast.structured()).isEqualTo(Map.of("city", "Oslo", "tempC", 21));
        assertThat(forecast.text()).contains("\"city\":\"Oslo\"");
    }

    @Test
    void failuresBecomeErrorOutcomesAndProtoweftExceptionsPropagate() {
        LocalTools tools = localTools();

        ToolOutcome failed = tools.invoke(ToolInvocation.of("explode", Map.of()));
        assertThat(failed.error()).isTrue();
        assertThat(failed.text()).isEqualTo("boom");
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("unreachable", Map.of())))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONNECTION));
    }

    @Test
    void stringResultsAreUnquoted() {
        assertThat(SpringAiTools.toOutcome("\"pong\"").text()).isEqualTo("pong");
        assertThat(SpringAiTools.toOutcome("not json").text()).isEqualTo("not json");
        assertThat(SpringAiTools.toOutcome("[1,2]").structured()).isEqualTo(List.of(1, 2));
        assertThat(SpringAiTools.toOutcome("42").structured()).isNull();
    }

    @Test
    void invalidAndDuplicateNamesAreRejected() {
        ToolCallback[] math = MethodToolCallbackProvider.builder().toolObjects(new MathTools()).build().getToolCallbacks();

        assertThatThrownBy(() -> SpringAiTools.from(MethodToolCallbackProvider.builder().toolObjects(new BadName()).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad.name");
        assertThatThrownBy(() -> SpringAiTools.from(math[0], math[0]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }

    @Test
    void toolsServedBackToChatClientThroughProtoweft() {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.callTool("add", "{\"a\": 20, \"b\": 22}"),
                ScriptedChatModel.echoLastToolResult());

        String answer = ChatClient.create(model).prompt().user("add")
                .tools(ProtoweftToolCallbacks.from(localTools()))
                .call().content();

        assertThat(answer).isEqualTo("result: 42");
    }
}
