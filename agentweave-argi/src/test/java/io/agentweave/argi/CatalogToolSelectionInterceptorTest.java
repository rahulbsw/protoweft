package io.agentweave.argi;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.SourcePolicy;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.agentweave.argi.ScriptedChatModel.answer;
import static io.agentweave.argi.ScriptedChatModel.callTool;
import static io.agentweave.argi.ScriptedChatModel.echoToolResults;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogToolSelectionInterceptorTest {

    private static final Map<String, Object> ONE_STRING = Map.of("type", "object",
            "properties", Map.of("value", Map.of("type", "string")), "required", List.of("value"));
    private static final CallerIdentity ADMIN =
            new CallerIdentity("admin", Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("secrets:read")));

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private CapabilityCatalog catalog(FallbackPolicy.Mode fallback) {
        LocalTools math = LocalTools.builder()
                .tool("add", "Adds two integers", ArgiToolsTest.TWO_INTS, (args, caller) -> {
                    calls.add("add by " + caller.name());
                    return ToolOutcome.text(String.valueOf(((Number) args.get("a")).intValue()
                            + ((Number) args.get("b")).intValue()));
                })
                .tool("multiply", "Multiplies two integers", ArgiToolsTest.TWO_INTS,
                        (args, caller) -> ToolOutcome.text("product"))
                .build();
        LocalTools text = LocalTools.builder()
                .tool("uppercase", "Converts text to upper case", ONE_STRING,
                        (args, caller) -> ToolOutcome.text(String.valueOf(args.get("value")).toUpperCase()))
                .build();
        LocalTools vault = LocalTools.builder()
                .tool("read_secret", "Reads a secret from the vault", ONE_STRING, (args, caller) -> {
                    calls.add("read_secret by " + caller.name());
                    return ToolOutcome.text("s3cr3t");
                })
                .build();
        return CapabilityCatalog.builder()
                .source("math", math)
                .source("text", text)
                .source("vault", vault, SourcePolicy.builder().requiredScopes("secrets:read").build())
                .fallback(FallbackPolicy.of(fallback))
                .build();
    }

    private static ReactAgent agent(ScriptedChatModel model, CatalogToolSelectionInterceptor selection,
                                    ToolCallback... extra) {
        return ReactAgent.builder().name("selector").model(model)
                .interceptors(selection)
                .tools(List.of(extra))
                .saver(new MemorySaver())
                .build();
    }

    private static String ask(ReactAgent agent, String text, CallerIdentity caller) throws Exception {
        RunnableConfig.Builder config = RunnableConfig.builder().threadId(UUID.randomUUID().toString());
        return agent.call(text, ArgiTools.withCaller(config, caller).build()).getText();
    }

    private static ToolCallback echo() {
        return FunctionToolCallback.builder("echo", (String s) -> s).description("Echoes").inputType(String.class).build();
    }

    @Test
    void modelIsOfferedOnlyTheCatalogHitsAndTheCallRuns() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(callTool("math__add", "{\"a\":2,\"b\":5}"), echoToolResults());
        AtomicReference<DiscoveryResult> seen = new AtomicReference<>();
        CatalogToolSelectionInterceptor selection = CatalogToolSelectionInterceptor
                .builder(catalog(FallbackPolicy.Mode.NONE)).onResult((r, result) -> seen.set(result)).build();

        assertThat(ask(agent(model, selection), "add two integers", CallerIdentity.of("alice"))).isEqualTo("result: 7");

        assertThat(model.toolNames(0)).contains("math__add").doesNotContain("text__uppercase", "vault__read_secret");
        assertThat(calls).containsExactly("add by alice");
        assertThat(seen.get().stage()).isEqualTo(FallbackPolicy.Stage.NONE);
    }

    @Test
    void permissionHiddenToolsNeverAppearEvenOnFallback() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(answer("no"), callTool("vault__read_secret", "{\"value\":\"k\"}"),
                echoToolResults());
        CatalogToolSelectionInterceptor selection = CatalogToolSelectionInterceptor
                .from(catalog(FallbackPolicy.Mode.NONE));
        ReactAgent agent = agent(model, selection);

        ask(agent, "read a secret from the vault", CallerIdentity.ANONYMOUS);
        // No permitted hit: KEEP_ALL offers every tool the caller may see, never the vault.
        assertThat(model.toolNames(0)).containsExactly("math__add", "math__multiply", "text__uppercase");

        assertThat(ask(agent, "read a secret from the vault", ADMIN)).isEqualTo("result: s3cr3t");
        assertThat(model.toolNames(1)).contains("vault__read_secret");
        assertThat(calls).containsExactly("read_secret by admin");
    }

    @Test
    void allSchemasFallbackOffersEveryPermittedCapability() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(answer("ok"));
        AtomicReference<DiscoveryResult> seen = new AtomicReference<>();
        CatalogToolSelectionInterceptor selection = CatalogToolSelectionInterceptor
                .builder(catalog(FallbackPolicy.Mode.ALL_SCHEMAS)).onResult((r, result) -> seen.set(result)).build();

        ask(agent(model, selection), "zzqx wobble", CallerIdentity.ANONYMOUS);

        assertThat(seen.get().stage()).isEqualTo(FallbackPolicy.Stage.ALL_SCHEMAS);
        assertThat(model.toolNames(0)).containsExactly("math__add", "math__multiply", "text__uppercase");
    }

    @Test
    void keepNoneOffersOnlyAlwaysVisibleAndUncataloguedTools() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(answer("a"), answer("b"), answer("c"));
        CapabilityCatalog catalog = catalog(FallbackPolicy.Mode.NONE);

        ask(agent(model, CatalogToolSelectionInterceptor.builder(catalog)
                .whenNothingFound(CatalogToolSelectionInterceptor.NoMatch.KEEP_NONE).build(), echo()),
                "zzqx wobble", CallerIdentity.ANONYMOUS);
        assertThat(model.toolNames(0)).containsExactly("echo");

        ask(agent(model, CatalogToolSelectionInterceptor.builder(catalog)
                .whenNothingFound(CatalogToolSelectionInterceptor.NoMatch.KEEP_NONE)
                .keepUncataloguedTools(false).alwaysVisible("text__uppercase").build(), echo()),
                "zzqx wobble", CallerIdentity.ANONYMOUS);
        assertThat(model.toolNames(1)).containsExactly("text__uppercase");

        ask(agent(model, CatalogToolSelectionInterceptor.builder(catalog)
                .whenNothingFound(CatalogToolSelectionInterceptor.NoMatch.KEEP_NONE)
                .keepUncataloguedTools(false).build(), echo()),
                "zzqx wobble", CallerIdentity.ANONYMOUS);
        assertThat(model.toolNames(2)).isEmpty();
    }

    @Test
    void callerFunctionAndQueryCustomizerApply() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(answer("ok"));
        AtomicReference<DiscoveryResult> seen = new AtomicReference<>();
        CatalogToolSelectionInterceptor selection = CatalogToolSelectionInterceptor.builder(catalog(FallbackPolicy.Mode.NONE))
                .callerIdentity(() -> ADMIN)
                .queryCustomizer((r, q) -> q.sources("vault"))
                .onResult((r, result) -> seen.set(result))
                .build();

        ask(agent(model, selection), "read a secret", CallerIdentity.ANONYMOUS);

        // The fixed caller has the scope; the customizer limits the search to the vault source.
        assertThat(model.toolNames(0)).containsExactly("vault__read_secret");
        assertThat(seen.get().names()).containsExactly("vault.read_secret");
    }

    @Test
    void queryTextUsesTheLatestUserMessageAndOptionalContext() {
        CatalogToolSelectionInterceptor plain = CatalogToolSelectionInterceptor.from(catalog(FallbackPolicy.Mode.NONE));
        CatalogToolSelectionInterceptor withContext = CatalogToolSelectionInterceptor
                .builder(catalog(FallbackPolicy.Mode.NONE)).contextMessages(2).build();
        List<org.springframework.ai.chat.messages.Message> messages = List.of(new SystemMessage("system"),
                new UserMessage("first"), AssistantMessage.builder().content("reply").build(),
                new UserMessage("second"), AssistantMessage.builder().content("").build());

        assertThat(plain.queryText(messages)).isEqualTo("second");
        assertThat(withContext.queryText(messages)).isEqualTo("first\nreply\nsecond");
        assertThat(plain.queryText(List.of(new SystemMessage("only")))).isEmpty();
        assertThat(plain.getTools()).extracting(t -> t.getToolDefinition().name())
                .containsExactly("math__add", "math__multiply", "text__uppercase", "vault__read_secret");
        assertThat(CatalogToolSelectionInterceptor.builder(catalog(FallbackPolicy.Mode.NONE))
                .registerCatalogTools(false).build().getTools()).isEmpty();
    }
}
