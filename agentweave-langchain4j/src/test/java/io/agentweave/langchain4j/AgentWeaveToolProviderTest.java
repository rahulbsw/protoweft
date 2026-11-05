package io.agentweave.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWeaveToolProviderTest {

    interface Assistant {
        String chat(String message);
    }

    interface AsyncAssistant {
        CompletableFuture<String> chat(String message);
    }

    private static final Map<String, Object> ADD_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "a", Map.of("type", "integer", "description", "first"),
                    "b", Map.of("type", "integer", "description", "second")),
            "required", List.of("a", "b"));

    private static final ToolProviderRequest REQUEST = new ToolProviderRequest("memory", UserMessage.from("hi"));

    private final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();
    private final List<CallerIdentity> callers = new CopyOnWriteArrayList<>();

    private ToolRegistry registry() {
        LocalTools local = LocalTools.builder()
                .tool("add", "Adds two integers", ADD_SCHEMA, (args, caller) -> {
                    calls.add(args);
                    callers.add(caller);
                    return ToolOutcome.text(String.valueOf(((Number) args.get("a")).intValue()
                            + ((Number) args.get("b")).intValue()));
                })
                .tool("broken", "Always fails", Map.of("type", "object"),
                        (args, caller) -> ToolOutcome.failure("disk on fire"))
                .tool("strict", "Rejects arguments", Map.of("type", "object"), (args, caller) -> {
                    throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, null, "x is required");
                })
                .tool("down", "Unreachable", Map.of("type", "object"), (args, caller) -> {
                    throw new AgentWeaveException(ErrorCategory.CONNECTION, null, "peer gone");
                })
                .build();
        return new ToolRegistry().register("local", local);
    }

    private static ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id("1").name(name).arguments(arguments).build();
    }

    private static ToolExecutor executor(AgentWeaveToolProvider provider, String llmName) {
        return tool(provider.provideTools(REQUEST), llmName).toolExecutor();
    }

    private static AiServiceTool tool(ToolProviderResult result, String llmName) {
        return result.aiServiceTools().stream().filter(t -> t.name().equals(llmName)).findFirst().orElseThrow();
    }

    @Test
    void aiServiceCallsAgentWeaveToolAndModelReceivesResult() {
        ScriptedChatModel model = new ScriptedChatModel("local__add", "{\"a\": 2, \"b\": 3}");
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(AgentWeaveToolProvider.from(registry()))
                .build();

        String answer = assistant.chat("What is 2 + 3?");

        assertThat(answer).isEqualTo("The answer is 5");
        assertThat(calls).containsExactly(Map.of("a", 2, "b", 3));
        assertThat(callers).containsExactly(CallerIdentity.ANONYMOUS);
        ToolSpecification offered = model.requests.get(0).toolSpecifications().stream()
                .filter(s -> s.name().equals("local__add")).findFirst().orElseThrow();
        assertThat(offered.description()).isEqualTo("Adds two integers");
        assertThat(offered.parameters().required()).containsExactlyInAnyOrder("a", "b");
        assertThat(offered.parameters().properties().get("a")).isInstanceOf(JsonIntegerSchema.class);
        assertThat(model.lastToolResult().text()).isEqualTo("5");
        assertThat(model.lastToolResult().isError()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void namesAreSanitizedAndBoundToTheOriginal() {
        AgentWeaveToolProvider provider = AgentWeaveToolProvider.from(registry());
        ToolProviderResult result = provider.provideTools(REQUEST);

        assertThat(result.aiServiceTools()).extracting(AiServiceTool::name)
                .containsExactlyInAnyOrder("local__add", "local__broken", "local__strict", "local__down");
        assertThat(tool(result, "local__add").toolSpecification().metadata())
                .containsEntry(AgentWeaveToolProvider.TOOL_NAME_METADATA, "local.add");
        assertThat(provider.originalName("local__add")).contains("local.add");
        assertThat(provider.originalName("nope")).isEmpty();
        assertThat(provider.isDynamic()).isFalse();
        assertThat(AgentWeaveToolProvider.builder(registry()).dynamic(true).build().isDynamic()).isTrue();
    }

    @Test
    void nameClashesAreRejected() {
        ToolRegistry clashing = new ToolRegistry()
                .register("x", LocalTools.builder().tool("y__z", "", Map.of(), (a, c) -> null).build())
                .register("x__y", LocalTools.builder().tool("z", "", Map.of(), (a, c) -> null).build());

        assertThatThrownBy(() -> AgentWeaveToolProvider.from(clashing).provideTools(REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x__y__z");
    }

    @Test
    void filterAndCustomNames() {
        AgentWeaveToolProvider provider = AgentWeaveToolProvider.builder(registry())
                .filter(spec -> spec.name().endsWith(".add"))
                .toolNames(name -> "aw_" + name.replace('.', '_'))
                .build();

        ToolProviderResult result = provider.provideTools(REQUEST);

        assertThat(result.aiServiceTools()).extracting(AiServiceTool::name).containsExactly("aw_local_add");
        assertThat(tool(result, "aw_local_add").toolExecutor().execute(call("aw_local_add", "{\"a\":1,\"b\":1}"), null))
                .isEqualTo("2");
    }

    @Test
    void toolFailureIsAnErrorResultTheModelSees() {
        ToolExecutionResult result = executor(AgentWeaveToolProvider.from(registry()), "local__broken")
                .executeWithContext(call("local__broken", "{}"), null);

        assertThat(result.isError()).isTrue();
        assertThat(result.resultText()).isEqualTo("disk on fire");
        assertThat(result.result()).isInstanceOf(ToolOutcome.class);

        ScriptedChatModel model = new ScriptedChatModel("local__broken", "{}");
        String answer = AiServices.builder(Assistant.class).chatModel(model)
                .toolProvider(AgentWeaveToolProvider.from(registry())).build().chat("go");
        assertThat(answer).isEqualTo("Tool failed: disk on fire");
        assertThat(model.lastToolResult().isError()).isTrue();
    }

    @Test
    void agentWeaveExceptionsAreWrappedForLangChain4jErrorHandlers() {
        AgentWeaveToolProvider provider = AgentWeaveToolProvider.from(registry());

        assertThatThrownBy(() -> executor(provider, "local__strict").executeWithContext(call("local__strict", "{}"), null))
                .isInstanceOf(ToolArgumentsException.class)
                .hasCauseInstanceOf(AgentWeaveException.class)
                .hasMessage("x is required");
        assertThatThrownBy(() -> executor(provider, "local__down").executeWithContext(call("local__down", "{}"), null))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("peer gone")
                .cause().isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONNECTION));
        assertThatThrownBy(() -> executor(provider, "local__add").executeWithContext(call("local__add", "[1,2"), null))
                .isInstanceOf(ToolArgumentsException.class)
                .hasMessageContaining("not a JSON object");

        // Default sync AI Service handling sends execution errors to the model.
        ScriptedChatModel model = new ScriptedChatModel("local__down", "{}");
        String answer = AiServices.builder(Assistant.class).chatModel(model).toolProvider(provider).build().chat("go");
        assertThat(answer).isEqualTo("Tool failed: peer gone");
        // ...and fails the invocation on argument errors, surfacing the AgentWeaveException itself.
        Assistant strict = AiServices.builder(Assistant.class)
                .chatModel(new ScriptedChatModel("local__strict", "{}")).toolProvider(provider).build();
        assertThatThrownBy(() -> strict.chat("go"))
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void callerIdentityIsPassedThrough() {
        AgentWeaveToolProvider fixed = AgentWeaveToolProvider.builder(registry())
                .callerIdentity(() -> CallerIdentity.of("alice")).build();
        executor(fixed, "local__add").execute(call("local__add", "{\"a\":1,\"b\":2}"), "memory-1");

        AgentWeaveToolProvider fromContext = AgentWeaveToolProvider.builder(registry())
                .callerIdentityFrom(ctx -> CallerIdentity.of("user-of-" + ctx.chatMemoryId())).build();
        executor(fromContext, "local__add").executeWithContext(call("local__add", "{\"a\":1,\"b\":2}"),
                InvocationContext.builder().chatMemoryId("conv-7").build());

        assertThat(callers).extracting(CallerIdentity::name).containsExactly("alice", "user-of-conv-7");
    }

    @Test
    void executeAsyncRunsOnTheConfiguredExecutor() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        AgentWeaveToolProvider provider = AgentWeaveToolProvider.builder(registry())
                .asyncExecutor(r -> {
                    Thread t = new Thread(r, "custom-tool-thread");
                    t.start();
                })
                .callerIdentity(() -> {
                    threads.add(Thread.currentThread().getName());
                    return CallerIdentity.ANONYMOUS;
                })
                .build();

        ToolExecutionResult result = executor(provider, "local__add")
                .executeAsync(call("local__add", "{\"a\":4,\"b\":5}"), null).get(5, TimeUnit.SECONDS);

        assertThat(result.resultText()).isEqualTo("9");
        assertThat(threads).containsExactly("custom-tool-thread");
    }

    @Test
    void asyncAiServiceUsesExecuteAsync() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel("local__add", "{\"a\": 20, \"b\": 22}");
        AsyncAssistant assistant = AiServices.builder(AsyncAssistant.class)
                .chatModel(model)
                .toolProvider(AgentWeaveToolProvider.from(registry()))
                .build();

        assertThat(assistant.chat("sum?").get(10, TimeUnit.SECONDS)).isEqualTo("The answer is 42");
        assertThat(calls).containsExactly(Map.of("a", 20, "b", 22));
    }
}
