package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoweftToolCallbacksTest {

    private static final Map<String, Object> TWO_INTS = Map.of("type", "object",
            "properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
            "required", List.of("a", "b"));

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private ToolRegistry registry() {
        LocalTools math = LocalTools.builder()
                .tool("add", "Adds two integers", TWO_INTS, (args, caller) -> {
                    calls.add("add by " + caller.name());
                    return ToolOutcome.text(String.valueOf(((Number) args.get("a")).intValue()
                            + ((Number) args.get("b")).intValue()));
                })
                .tool("divide", "Divides two integers", TWO_INTS, (args, caller) ->
                        ((Number) args.get("b")).intValue() == 0
                                ? ToolOutcome.failure("division by zero")
                                : ToolOutcome.text("ok"))
                .tool("stats", "Structured result", Map.of(), (args, caller) ->
                        new ToolOutcome(List.of(), false, Map.of("count", 3)))
                .tool("broken", "Always throws an ProtoweftException", Map.of(), (args, caller) -> {
                    throw new ProtoweftException(ErrorCategory.CONNECTION, null, "server gone");
                })
                .build();
        return new ToolRegistry().register("math", math);
    }

    @Test
    void chatClientCallsRegistryToolWithParsedArgumentsAndCaller() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.callTool("math__add", "{\"a\": 2, \"b\": 40}"),
                ScriptedChatModel.echoLastToolResult());
        ChatClient chat = ChatClient.create(model);

        String answer = chat.prompt().user("add 2 and 40")
                .tools(ProtoweftToolCallbacks.from(registry()))
                .toolContext(Map.of(ProtoweftToolCallbacks.CALLER_KEY, CallerIdentity.of("alice")))
                .call().content();

        assertThat(answer).isEqualTo("result: 42");
        assertThat(calls).containsExactly("add by alice");
        assertThat(model.toolNames(0)).containsExactly("math__add", "math__broken", "math__divide", "math__stats");
        assertThat(model.inputSchema(0, "math__add")).contains("\"required\"").contains("\"integer\"");
    }

    @Test
    void errorOutcomeAndProtoweftExceptionReachTheModelAsMessages() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.callTool("math__divide", "{\"a\": 1, \"b\": 0}"),
                ScriptedChatModel.echoLastToolResult(),
                ScriptedChatModel.callTool("math__broken", "{}"),
                ScriptedChatModel.echoLastToolResult(),
                ScriptedChatModel.callTool("math__add", "not json"),
                ScriptedChatModel.echoLastToolResult());
        ChatClient chat = ChatClient.builder(model).defaultTools(ProtoweftToolCallbacks.from(registry())).build();

        assertThat(chat.prompt().user("divide").call().content()).isEqualTo("result: division by zero");
        assertThat(chat.prompt().user("broken").call().content()).isEqualTo("result: server gone");
        assertThat(chat.prompt().user("bad args").call().content())
                .startsWith("result: arguments for tool 'math__add' are not a JSON object");
        assertThat(calls).isEmpty();
    }

    @Test
    void failuresAreToolExecutionExceptionsWithTheCause() {
        ToolCallback[] callbacks = ProtoweftToolCallbacks.from(registry()).getToolCallbacks();
        ToolCallback divide = byName(callbacks, "math__divide");
        ToolCallback broken = byName(callbacks, "math__broken");

        assertThatThrownBy(() -> divide.call("{\"a\":1,\"b\":0}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("division by zero")
                .cause().isInstanceOfSatisfying(ToolOutcomeException.class,
                        e -> assertThat(e.outcome().error()).isTrue());
        assertThatThrownBy(() -> broken.call("{}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause().isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONNECTION));
        assertThatThrownBy(() -> divide.call("[1]"))
                .cause().isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void structuredResultIsJsonAndEmptySchemaBecomesObject() {
        ToolCallback stats = byName(ProtoweftToolCallbacks.from(registry()).getToolCallbacks(), "math__stats");

        assertThat(stats.call("")).isEqualTo("{\"count\":3}");
        assertThat(stats.getToolDefinition().inputSchema()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
        assertThat(stats.getToolDefinition().description()).isEqualTo("Structured result");
    }

    @Test
    void callerFunctionAndAnonymousDefault() {
        ToolCallback add = byName(ProtoweftToolCallbacks.builder(registry())
                .callerIdentityFrom(ctx -> CallerIdentity.of(String.valueOf(ctx.getContext().get("user"))))
                .build().getToolCallbacks(), "math__add");
        add.call("{\"a\":1,\"b\":1}", new ToolContext(Map.of("user", "bob")));
        byName(ProtoweftToolCallbacks.from(registry()).getToolCallbacks(), "math__add").call("{\"a\":1,\"b\":1}");

        assertThat(calls).containsExactly("add by bob", "add by anonymous");
    }

    @Test
    void namesMapBackAndClashesFail() {
        ProtoweftToolCallbacks callbacks = ProtoweftToolCallbacks.builder(registry())
                .filter(spec -> spec.name().endsWith("add")).build();
        assertThat(callbacks.getToolCallbacks()).hasSize(1);
        assertThat(callbacks.originalName("math__add")).contains("math.add");

        ToolSource clashing = source(List.of(new ToolSpec("a.b", "", Map.of()), new ToolSpec("a__b", "", Map.of())));
        assertThatThrownBy(() -> ProtoweftToolCallbacks.from(clashing).getToolCallbacks())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("a__b");
    }

    @Test
    void dynamicListingRelistsAndSnapshotDoesNot() {
        AtomicInteger listings = new AtomicInteger();
        List<ToolSpec> specs = new ArrayList<>(List.of(new ToolSpec("one", "first", Map.of())));
        ToolSource source = new ToolSource() {
            @Override
            public List<ToolSpec> listTools() {
                listings.incrementAndGet();
                return List.copyOf(specs);
            }

            @Override
            public ToolOutcome invoke(ToolInvocation invocation) {
                return ToolOutcome.text("x");
            }
        };
        ProtoweftToolCallbacks dynamic = ProtoweftToolCallbacks.from(source);
        ProtoweftToolCallbacks snapshot = ProtoweftToolCallbacks.builder(source).dynamic(false).build();

        dynamic.getToolCallbacks();
        snapshot.getToolCallbacks();
        specs.add(new ToolSpec("two", "second", Map.of()));

        assertThat(dynamic.getToolCallbacks()).hasSize(2);
        assertThat(snapshot.getToolCallbacks()).hasSize(1);
        snapshot.refresh();
        assertThat(snapshot.getToolCallbacks()).hasSize(2);
        assertThat(listings).hasValue(4);
    }

    @Test
    void sanitizeMatchesProviderToolNames() {
        assertThat(ToolNames.sanitize("weather.forecast")).isEqualTo("weather__forecast");
        assertThat(ToolNames.sanitize("a b/c")).isEqualTo("a_b_c");
        String longName = "source." + "x".repeat(80);
        String mapped = ToolNames.sanitize(longName);
        assertThat(mapped).hasSize(64).matches(ToolNames.PROVIDER_SAFE);
        assertThat(mapped).isEqualTo(new io.github.rahulbsw.protoweft.discovery.ProviderToolNames().providerName(longName));
    }

    @Test
    void textForRendersEveryPart() {
        ToolOutcome outcome = new ToolOutcome(List.of(new Content.Text("t"), new Content.Json(Map.of("k", 1)),
                new Content.Link("https://x", null, null), new Content.Data("image/png", "AA=="),
                new Content.Unmapped("audio")), false, null);

        assertThat(ProtoweftToolCallback.textFor(outcome).split("\n"))
                .containsExactly("t", "{\"k\":1}", "https://x", "[image/png data not forwarded]", "[unmapped audio content]");
    }

    private static ToolCallback byName(ToolCallback[] callbacks, String name) {
        for (ToolCallback c : callbacks) {
            if (c.getToolDefinition().name().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no callback " + name);
    }

    private static ToolSource source(List<ToolSpec> specs) {
        return new ToolSource() {
            @Override
            public List<ToolSpec> listTools() {
                return specs;
            }

            @Override
            public ToolOutcome invoke(ToolInvocation invocation) {
                return ToolOutcome.text("x");
            }
        };
    }
}
