package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.rahulbsw.protoweft.argi.ScriptedChatModel.callTool;
import static io.github.rahulbsw.protoweft.argi.ScriptedChatModel.echoToolResults;
import static org.assertj.core.api.Assertions.assertThat;

class ArgiToolsTest {

    static final Map<String, Object> TWO_INTS = Map.of("type", "object",
            "properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
            "required", List.of("a", "b"));

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private ToolRegistry registry() {
        return new ToolRegistry().register("math", LocalTools.builder()
                .tool("add", "Adds two integers", TWO_INTS, (args, caller) -> {
                    calls.add("add by " + caller.name());
                    return ToolOutcome.text(String.valueOf(((Number) args.get("a")).intValue()
                            + ((Number) args.get("b")).intValue()));
                })
                .tool("fail", "Always fails", Map.of(), (args, caller) -> ToolOutcome.failure("no such luck"))
                .build());
    }

    @Test
    void reactAgentCallsProtoweftToolsAsTheCallerInTheRunMetadata() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(callTool("math__add", "{\"a\":2,\"b\":3}"), echoToolResults());
        ReactAgent agent = ReactAgent.builder().name("calc").model(model)
                .toolCallbackProviders(ArgiTools.callbackProvider(registry()))
                .saver(new MemorySaver())
                .build();

        String answer = agent.call("add 2 and 3", ArgiTools.withCaller(RunnableConfig.builder().threadId("t1"),
                CallerIdentity.of("alice")).build()).getText();

        assertThat(answer).isEqualTo("result: 5");
        assertThat(calls).containsExactly("add by alice");
        assertThat(model.toolNames(0)).containsExactly("math__add", "math__fail");
    }

    @Test
    void fixedCallerThroughTheAgentToolContextAndErrorsReachTheModel() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(callTool("math__add", "{\"a\":1,\"b\":1}"), echoToolResults(),
                callTool("math__fail", "{}"), echoToolResults());
        ReactAgent agent = ReactAgent.builder().name("calc").model(model)
                .toolCallbackProviders(ArgiTools.callbackProvider(registry()))
                .toolContext(Map.of(ArgiTools.CALLER_KEY, CallerIdentity.of("service")))
                .saver(new MemorySaver())
                .build();

        assertThat(agent.call("add", RunnableConfig.builder().threadId("t2").build()).getText()).isEqualTo("result: 2");
        assertThat(calls).containsExactly("add by service");
        assertThat(agent.call("fail", RunnableConfig.builder().threadId("t3").build()).getText())
                .contains("no such luck");
    }

    @Test
    void callerFromContextMaps() {
        assertThat(ArgiTools.callerFrom(null)).isEqualTo(CallerIdentity.ANONYMOUS);
        assertThat(ArgiTools.callerFrom(Map.of(ArgiTools.CALLER_KEY, "not a caller"))).isEqualTo(CallerIdentity.ANONYMOUS);
        assertThat(ArgiTools.callerFrom(Map.of(ArgiTools.CALLER_KEY, CallerIdentity.of("bob"))).name()).isEqualTo("bob");
    }
}
