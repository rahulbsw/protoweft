package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolContextsTest {

    /** A protocol-specific context, as a protocol module would define it. */
    public interface GreetingContext extends ToolContext {
        String greeting();
    }

    public static class Tools {
        @AgentTool(description = "Greets")
        public String greet(String name, GreetingContext context) {
            return context.available() ? context.greeting() + ", " + name : "plain " + name;
        }

        @AgentTool(description = "Uses the context unconditionally")
        public String strict(GreetingContext context) {
            return context.greeting();
        }
    }

    private static final ToolContexts HELLO = ToolContexts.of(GreetingContext.class, () -> "hello");

    @Test
    void injectsContextParametersAndKeepsThemOutOfTheSchema() {
        LocalTools tools = LocalTools.builder().scan(new Tools()).build();
        ToolSpec greet = tools.listTools().stream().filter(t -> t.name().equals("greet")).findFirst().orElseThrow();
        assertThat(greet.inputSchema().get("properties")).isEqualTo(Map.of("name", Map.of("type", "string")));

        ToolOutcome with = tools.invoke(ToolInvocation.of("greet", Map.of("name", "Ada")), CallerIdentity.ANONYMOUS,
                ToolProgress.NOOP, HELLO);
        assertThat(with.text()).isEqualTo("hello, Ada");
        ToolOutcome without = tools.invoke(ToolInvocation.of("greet", Map.of("name", "Ada")));
        assertThat(without.text()).isEqualTo("plain Ada");
    }

    @Test
    void unavailableContextThrowsUnsupported() {
        LocalTools tools = LocalTools.builder().scan(new Tools()).build();
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("strict", Map.of())))
                .isInstanceOf(ProtoweftException.class)
                .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.UNSUPPORTED));
        GreetingContext standIn = ToolContexts.unavailable(GreetingContext.class);
        assertThat(standIn.available()).isFalse();
        assertThat(standIn.toString()).contains("GreetingContext");
        assertThat(standIn).isEqualTo(standIn);
    }

    @Test
    void programmaticHandlersAndRegistryPassContextsThrough() {
        LocalTools tools = LocalTools.builder()
                .tool("hi", "Says hi", Map.of("type", "object"),
                        (args, caller, progress, contexts) ->
                                ToolOutcome.text(contexts.resolve(GreetingContext.class).greeting()))
                .build();
        ToolRegistry registry = new ToolRegistry().register("local", tools);
        assertThat(registry.invoke(ToolInvocation.of("local.hi", Map.of()), CallerIdentity.ANONYMOUS,
                ToolProgress.NOOP, HELLO).text()).isEqualTo("hello");
        assertThat(HELLO.get(GreetingContext.class)).isPresent();
        assertThat(ToolContexts.EMPTY.get(GreetingContext.class)).isEmpty();
    }

    /** Not an interface, so it can't be a context type. */
    public static final class Concrete implements ToolContext {
    }

    public static class BadTools {
        @AgentTool(description = "Declares a class-typed context")
        public String bad(Concrete context) {
            return "x";
        }
    }

    @Test
    void contextTypesMustBeInterfaces() {
        assertThatThrownBy(() -> ToolContexts.unavailable(Concrete.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalTools.builder().scan(new BadTools()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interface");
    }
}
