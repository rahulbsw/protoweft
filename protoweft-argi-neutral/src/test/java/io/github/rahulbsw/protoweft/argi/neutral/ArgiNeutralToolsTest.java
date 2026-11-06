package io.github.rahulbsw.protoweft.argi.neutral;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.discovery.ProviderToolNames;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgiNeutralToolsTest {

    private static final Map<String, Object> CITY = Map.of("type", "object",
            "properties", Map.of("city", Map.of("type", "string")), "required", List.of("city"));
    private static final CallerIdentity ALICE = CallerIdentity.of("alice");

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private ToolRegistry protoweftTools() {
        LocalTools weather = LocalTools.builder()
                .tool("forecast", "Weather forecast for a city", CITY, (args, caller) -> {
                    calls.add("forecast(" + args.get("city") + ") by " + caller.name());
                    return ToolOutcome.text("sunny in " + args.get("city"));
                })
                .tool("broken", "Always fails", Map.of(), (args, caller) -> ToolOutcome.failure("station offline"))
                .tool("stats", "Structured only", Map.of("properties", Map.of()),
                        (args, caller) -> new ToolOutcome(List.of(), false, Map.of("temp", 21)))
                .build();
        return new ToolRegistry().register("weather", weather);
    }

    private static ToolResult run(Tool tool, String args, ToolContext context) {
        return tool.execute(new ToolCall("call-1", tool.definition().name(), args), context)
                .toCompletableFuture().join();
    }

    private static Tool find(io.github.agentic.ai.api.tool.ToolRegistry registry, String name) {
        return registry.find(name).orElseThrow();
    }

    @Test
    void registryOffersProviderSafeNamesAndJsonSchemas() {
        io.github.agentic.ai.api.tool.ToolRegistry registry = ArgiNeutralTools.registry(protoweftTools());

        assertThat(registry.definitions()).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("weather__forecast", "weather__broken", "weather__stats");
        ToolDefinition forecast = find(registry, "weather__forecast").definition();
        assertThat(forecast.description()).isEqualTo("Weather forecast for a city");
        assertThat(Json.parseObject(forecast.inputSchema())).isEqualTo(CITY);
        // Empty schemas and schemas without a type become object schemas.
        assertThat(Json.parseObject(find(registry, "weather__broken").definition().inputSchema()))
                .isEqualTo(Map.of("type", "object", "properties", Map.of()));
        assertThat(Json.parseObject(find(registry, "weather__stats").definition().inputSchema()))
                .containsEntry("type", "object");
        // Same names as Protoweft's own provider payloads.
        assertThat(ToolNames.sanitize("weather.forecast")).isEqualTo(new ProviderToolNames().providerName("weather.forecast"));
        String longName = "src." + "x".repeat(80);
        assertThat(ToolNames.sanitize(longName)).isEqualTo(new ProviderToolNames().providerName(longName)).hasSize(64);
    }

    @Test
    void executeCallsTheSourceAsTheCallerInTheToolContextOnTheExecutor() {
        AtomicInteger submitted = new AtomicInteger();
        Executor counting = command -> {
            submitted.incrementAndGet();
            command.run();
        };
        io.github.agentic.ai.api.tool.ToolRegistry registry =
                ArgiNeutralTools.builder(protoweftTools()).executor(counting).build();

        ToolResult result = run(find(registry, "weather__forecast"), "{\"city\":\"Paris\"}",
                new ToolContext(Map.of(ArgiNeutralTools.CALLER_KEY, ALICE)));
        ToolResult anonymous = registry.execute(new ToolCall("c2", "weather__forecast", "{\"city\":\"Rome\"}"),
                ToolContext.empty()).toCompletableFuture().join();

        assertThat(result.error()).isFalse();
        assertThat(result.content()).containsExactly(ContentPart.text("sunny in Paris"));
        assertThat(anonymous.content()).containsExactly(ContentPart.text("sunny in Rome"));
        assertThat(calls).containsExactly("forecast(Paris) by alice", "forecast(Rome) by anonymous");
        assertThat(submitted).hasValue(2);
    }

    @Test
    void failuresBecomeErrorResultsTheModelSees() {
        ToolSource throwing = new ToolSource() {
            @Override public List<ToolSpec> listTools() { return List.of(new ToolSpec("gone", "Gone", Map.of())); }
            @Override public ToolOutcome invoke(ToolInvocation invocation) {
                throw new ProtoweftException(ErrorCategory.CONNECTION, null, "server unreachable");
            }
        };
        io.github.agentic.ai.api.tool.ToolRegistry registry = ArgiNeutralTools.registry(protoweftTools());
        Tool gone = find(ArgiNeutralTools.registry(throwing), "gone");

        ToolResult failed = run(find(registry, "weather__broken"), "{}", ToolContext.empty());
        ToolResult invalid = run(find(registry, "weather__forecast"), "[1,2]", ToolContext.empty());
        ToolResult unreachable = run(gone, "{}", ToolContext.empty());

        assertThat(failed.error()).isTrue();
        assertThat(failed.content()).containsExactly(ContentPart.text("station offline"));
        assertThat(invalid.error()).isTrue();
        assertThat(Results.text(invalid)).contains("not a JSON object");
        assertThat(invalid.metadata()).containsEntry(Results.ERROR_CATEGORY, "INVALID_ARGUMENTS");
        assertThat(unreachable.error()).isTrue();
        assertThat(unreachable.content()).containsExactly(ContentPart.text("server unreachable"));
        assertThat(unreachable.metadata()).containsEntry(Results.ERROR_CATEGORY, "CONNECTION");
        assertThat(calls).isEmpty();
    }

    @Test
    void contentIsMappedPartByPart() {
        String png = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        ToolOutcome outcome = new ToolOutcome(List.of(new Content.Text("t"), new Content.Json(Map.of("a", 1)),
                new Content.Data("image/png", png), new Content.Link("https://x/y", null, null),
                new Content.Unmapped("mcp:resource")), false, null);

        ToolResult result = Results.toResult(outcome);
        ToolResult structuredOnly = run(find(ArgiNeutralTools.registry(protoweftTools()), "weather__stats"), "{}",
                ToolContext.empty());

        assertThat(result.content()).extracting(ContentPart::type).containsExactly(ContentPart.Type.TEXT,
                ContentPart.Type.JSON, ContentPart.Type.IMAGE, ContentPart.Type.TEXT, ContentPart.Type.TEXT);
        assertThat(result.content().get(1).text()).isEqualTo("{\"a\":1}");
        assertThat(result.content().get(2).data()).containsExactly(1, 2, 3);
        assertThat(result.content().get(3).text()).isEqualTo("https://x/y");
        assertThat(result.content().get(4).text()).contains("mcp:resource");
        assertThat(structuredOnly.content()).containsExactly(ContentPart.json("{\"temp\":21}"));
        // Back again: binary stays base64 data.
        ToolOutcome back = Results.toOutcome(result);
        assertThat(back.content().get(2)).isEqualTo(new Content.Data("image/png", png));
        assertThat(back.content().get(1)).isEqualTo(new Content.Json(Map.of("a", 1)));
    }

    @Test
    void namesThatClashFailAndFiltersApply() {
        assertThatThrownBy(() -> ArgiNeutralTools.builder(protoweftTools()).toolNames(name -> "same").build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'same'");
        assertThat(ArgiNeutralTools.builder(protoweftTools()).filter(spec -> spec.name().endsWith("forecast"))
                .build().definitions()).extracting(ToolDefinition::name).containsExactly("weather__forecast");
    }

    @Test
    void dynamicProviderListsTheSourceAgain() {
        List<ToolSpec> specs = new ArrayList<>(List.of(new ToolSpec("one", "1", Map.of())));
        ToolSource source = new ToolSource() {
            @Override public List<ToolSpec> listTools() { return List.copyOf(specs); }
            @Override public ToolOutcome invoke(ToolInvocation invocation) { return ToolOutcome.text(invocation.toolName()); }
        };
        var dynamic = ArgiNeutralTools.builder(source).dynamic();

        assertThat(dynamic.apply(Map.of()).definitions()).extracting(ToolDefinition::name).containsExactly("one");
        specs.add(new ToolSpec("two", "2", Map.of()));
        assertThat(dynamic.apply(Map.of()).definitions()).extracting(ToolDefinition::name).containsExactly("one", "two");
    }

    /** A hand-written ARGI tool: echoes its arguments as JSON and reports the caller in metadata. */
    private static final class EchoTool implements Tool {
        final List<ToolContext> contexts = new CopyOnWriteArrayList<>();
        private final String name;
        private final boolean direct;

        EchoTool(String name, boolean direct) {
            this.name = name;
            this.direct = direct;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "Echoes arguments", "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\"}}}");
        }

        @Override
        public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
            contexts.add(context);
            if (call.arguments().contains("fail")) {
                return CompletableFuture.completedFuture(ToolResult.error("echo refused"));
            }
            if (call.arguments().contains("boom")) {
                return CompletableFuture.failedFuture(new IllegalStateException("echo exploded"));
            }
            return CompletableFuture.completedFuture(new ToolResult(List.of(ContentPart.json(call.arguments())), false, Map.of()));
        }

        @Override
        public boolean returnDirect() {
            return direct;
        }
    }

    @Test
    void argiToolsBecomeLocalTools() {
        EchoTool echo = new EchoTool("echo", true);
        LocalTools local = LocalTools.builder().addAll(ArgiNeutralTools.toLocalTools(List.of(echo))).build();
        ToolProgress progress = ToolProgress.NOOP;

        ToolSpec spec = local.listTools().get(0);
        ToolOutcome ok = local.invoke(ToolInvocation.of("echo", Map.of("v", "hi")), ALICE, progress);
        ToolOutcome refused = local.invoke(ToolInvocation.of("echo", Map.of("v", "fail")), ALICE);
        ToolOutcome exploded = local.invoke(ToolInvocation.of("echo", Map.of("v", "boom")), ALICE);

        assertThat(spec.name()).isEqualTo("echo");
        assertThat(spec.inputSchema()).containsEntry("type", "object").containsKey("properties");
        assertThat(spec.metadata()).containsEntry(ArgiNeutralTools.META_RETURN_DIRECT, true);
        assertThat(ok.error()).isFalse();
        assertThat(ok.structured()).isEqualTo(Map.of("v", "hi"));
        assertThat(ok.content()).containsExactly(new Content.Json(Map.of("v", "hi")));
        assertThat(echo.contexts.get(0).attributes()).containsEntry(ArgiNeutralTools.CALLER_KEY, ALICE)
                .containsEntry(ArgiNeutralTools.PROGRESS_KEY, progress);
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).isEqualTo("echo refused");
        assertThat(exploded.error()).isTrue();
        assertThat(exploded.text()).isEqualTo("echo exploded");
    }

    @Test
    void toLocalToolsRejectsUnsafeOrDuplicateNames() {
        assertThatThrownBy(() -> ArgiNeutralTools.toLocalTools(List.of(new EchoTool("has.dot", false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has.dot");
        assertThatThrownBy(() -> ArgiNeutralTools.toLocalTools(List.of(new EchoTool("e", false), new EchoTool("e", false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }

    @Test
    void roundTripProtoweftToArgiAndBack() {
        io.github.agentic.ai.api.tool.ToolRegistry argi = ArgiNeutralTools.registry(protoweftTools());
        LocalTools back = LocalTools.builder().addAll(ArgiNeutralTools.toLocalTools(argi)).build();

        ToolOutcome outcome = back.invoke(ToolInvocation.of("weather__forecast", Map.of("city", "Oslo")), ALICE);
        ToolOutcome failure = back.invoke(ToolInvocation.of("weather__broken", Map.of()), ALICE);

        assertThat(back.listTools()).extracting(ToolSpec::name)
                .containsExactly("weather__broken", "weather__forecast", "weather__stats");
        assertThat(back.listTools().get(1).inputSchema()).isEqualTo(CITY);
        assertThat(outcome.text()).isEqualTo("sunny in Oslo");
        assertThat(failure.error()).isTrue();
        assertThat(failure.text()).isEqualTo("station offline");
        assertThat(calls).containsExactly("forecast(Oslo) by alice");
    }
}
