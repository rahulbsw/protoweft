package io.agentweave.argi.neutral;

import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentRequest;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.SourcePolicy;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.interceptor.authorization.AuthorizationDecision;
import io.github.agentic.ai.agent.react.HumanInTheLoop;
import io.github.agentic.ai.agent.react.ReActAgent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A real (Spring-free) ARGI {@link ReActAgent} driven by a scripted neutral ChatClient. */
class ReActAgentIntegrationTest {

    private static final Map<String, Object> CITY = Map.of("type", "object",
            "properties", Map.of("city", Map.of("type", "string")), "required", List.of("city"));
    private static final Map<String, Object> TEXT = Map.of("type", "object",
            "properties", Map.of("value", Map.of("type", "string")));
    private static final CallerIdentity BOB = CallerIdentity.of("bob");
    private static final CallerIdentity ADMIN =
            new CallerIdentity("admin", Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("secrets:read")));

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private LocalTools weather() {
        return LocalTools.builder()
                .tool("forecast", "Weather forecast for a city", CITY, (args, caller) -> {
                    calls.add("forecast(" + args.get("city") + ") by " + caller.name());
                    return ToolOutcome.text("sunny in " + args.get("city"));
                })
                .build();
    }

    private CapabilityCatalog catalog() {
        LocalTools billing = LocalTools.builder()
                .tool("refund", "Refund an invoice payment", TEXT, (args, caller) -> ToolOutcome.text("refunded"))
                .build();
        LocalTools vault = LocalTools.builder()
                .tool("read_secret", "Read a secret from the vault", TEXT, (args, caller) -> {
                    calls.add("read_secret by " + caller.name());
                    return ToolOutcome.text("s3cr3t");
                })
                .build();
        return CapabilityCatalog.builder()
                .source("weather", weather())
                .source("billing", billing)
                .source("vault", vault, SourcePolicy.builder().requiredScopes("secrets:read").build())
                .fallback(FallbackPolicy.of(FallbackPolicy.Mode.NONE))
                .build();
    }

    private static AgentRequest request(String text, String taskId, boolean resumed, CallerIdentity caller) {
        return new AgentRequest(AgentMessage.user(text), taskId, "ctx", resumed, List.of(), caller);
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.tools().stream().map(ToolDefinition::name).toList();
    }

    @Test
    void reactAgentCallsAgentWeaveToolsAsTheCallerAndStreams() throws Exception {
        ScriptedChatClient chat = new ScriptedChatClient(
                ScriptedChatClient.callTool("weather__forecast", "{\"city\":\"Paris\"}"),
                ScriptedChatClient.answer("It is sunny in Paris"));
        ReActAgent agent = ReActAgent.builder(chat)
                .tools(ArgiNeutralTools.registry(new ToolRegistry().register("weather", weather())))
                .streamModel(true)
                .build();
        FakeTask task = new FakeTask("t1");

        ArgiNeutralAgents.handler(agent).handle(request("Weather in Paris?", "t1", false, BOB), task);

        assertThat(calls).containsExactly("forecast(Paris) by bob");
        assertThat(toolNames(chat.requests.get(0))).containsExactly("weather__forecast");
        ChatMessage toolResult = chat.requests.get(1).messages().get(2);
        assertThat(toolResult.role()).isEqualTo(ChatRole.TOOL);
        assertThat(toolResult.text()).isEqualTo("sunny in Paris");
        assertThat(task.events).containsSubsequence("working:calling weather__forecast", "working:It ",
                "working:Paris", "artifact:response:It is sunny in Paris", "complete");
    }

    @Test
    void catalogInterceptorShowsOnlyTheHitsAndCallsThroughTheCatalog() throws Exception {
        List<DiscoveryResult> results = new CopyOnWriteArrayList<>();
        ScriptedChatClient chat = new ScriptedChatClient(
                ScriptedChatClient.callTool("weather__forecast", "{\"city\":\"Oslo\"}"),
                ScriptedChatClient.answer("Sunny"));
        LocalTools clock = LocalTools.builder()
                .tool("now", "Current time", Map.of(), (args, caller) -> ToolOutcome.text("noon")).build();
        ReActAgent agent = ReActAgent.builder(chat)
                .tools(ArgiNeutralTools.registry(clock))
                .interceptors(List.of(CatalogToolSelectionInterceptor.builder(catalog())
                        .onResult((req, result) -> results.add(result)).build()))
                .build();
        FakeTask task = new FakeTask("t2");

        ArgiNeutralAgents.handler(agent).handle(request("What is the weather forecast for Oslo?", "t2", false, BOB), task);

        assertThat(toolNames(chat.requests.get(0))).containsExactlyInAnyOrder("weather__forecast", "now");
        assertThat(calls).containsExactly("forecast(Oslo) by bob");
        assertThat(task.events).containsSubsequence("artifact:response:Sunny", "complete");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).names()).contains("weather.forecast");
    }

    @Test
    void catalogPermissionsApplyToTheSearchingCaller() throws Exception {
        CapabilityCatalog catalog = catalog();
        ScriptedChatClient forBob = new ScriptedChatClient(ScriptedChatClient.answer("no access"));
        ScriptedChatClient forAdmin = new ScriptedChatClient(
                ScriptedChatClient.callTool("vault__read_secret", "{\"value\":\"db\"}"),
                ScriptedChatClient.answer("got it"));
        CatalogToolSelectionInterceptor onlyHits = CatalogToolSelectionInterceptor.builder(catalog)
                .keepOtherTools(false).build();

        ArgiNeutralAgents.handler(ReActAgent.builder(forBob).interceptors(List.of(onlyHits)).build())
                .handle(request("read the vault secret", "t3", false, BOB), new FakeTask("t3"));
        ArgiNeutralAgents.handler(ReActAgent.builder(forAdmin).interceptors(List.of(onlyHits)).build())
                .handle(request("read the vault secret", "t4", false, ADMIN), new FakeTask("t4"));

        assertThat(toolNames(forBob.requests.get(0))).doesNotContain("vault__read_secret");
        assertThat(toolNames(forAdmin.requests.get(0))).contains("vault__read_secret").doesNotContain("now");
        assertThat(calls).containsExactly("read_secret by admin");
    }

    @Test
    void agentWeaveInputsCanBeHiddenFromTheChatClientButStillReachTools() throws Exception {
        ScriptedChatClient chat = new ScriptedChatClient(
                ScriptedChatClient.callTool("weather__forecast", "{\"city\":\"Lima\"}"),
                ScriptedChatClient.answer("ok"));
        ReActAgent agent = ReActAgent.builder(ArgiNeutralAgents.withoutAgentWeaveAttributes(chat))
                .tools(ArgiNeutralTools.registry(new ToolRegistry().register("weather", weather())))
                .build();
        FakeTask task = new FakeTask("t6");

        ArgiNeutralAgents.builder(agent)
                .requestCustomizer((request, argi) -> {
                    Map<String, Object> inputs = new java.util.HashMap<>(argi.inputs());
                    inputs.put("app.locale", "es");
                    return new io.github.agentic.ai.agent.AgentRequest(argi.messages(), inputs, argi.resumeToken());
                })
                .build().handle(request("Weather in Lima?", "t6", false, BOB), task);

        assertThat(chat.requests).allSatisfy(r -> assertThat(r.attributes()).isEqualTo(Map.of("app.locale", "es")));
        assertThat(calls).containsExactly("forecast(Lima) by bob");
        assertThat(task.events).containsSubsequence("artifact:response:ok", "complete");
    }

    /** Suspends on denied tools; the token names the pending call. */
    private static final class Approvals implements HumanInTheLoop {
        final List<String> validated = new CopyOnWriteArrayList<>();

        @Override
        public void validateResume(io.github.agentic.ai.agent.AgentRequest request, AgentExecutionContext context) {
            validated.add(request.resumeToken() + " by " + context.trusted().principal().id());
        }

        @Override
        public AgentOutcome.Suspension suspend(HumanRequest request, AgentExecutionContext context) {
            return new AgentOutcome.Suspension("approve-" + request.toolCallId(),
                    Map.of("approval", request.reason() + ": " + request.toolName()));
        }
    }

    @Test
    void humanInTheLoopSuspensionRoundTripsAsInputRequired() throws Exception {
        Approvals approvals = new Approvals();
        ScriptedChatClient chat = new ScriptedChatClient(
                ScriptedChatClient.callTool("weather__forecast", "{\"city\":\"Rome\"}"),
                req -> ScriptedChatClient.answer("decision=" + req.attributes().get(ArgiNeutralAgents.REPLY_KEY))
                        .apply(req));
        ReActAgent agent = ReActAgent.builder(chat)
                .tools(ArgiNeutralTools.registry(new ToolRegistry().register("weather", weather())))
                .authorizationPolicy((tool, ctx) -> AuthorizationDecision.deny("Needs approval"))
                .humanInTheLoop(approvals)
                .build();
        var handler = ArgiNeutralAgents.handler(agent);
        FakeTask first = new FakeTask("t5");
        FakeTask second = new FakeTask("t5");

        handler.handle(request("Weather in Rome?", "t5", false, BOB), first);
        handler.handle(request("approve", "t5", true, BOB), second);

        assertThat(first.events).containsSubsequence("working:calling weather__forecast",
                "input:Needs approval: weather__forecast");
        assertThat(approvals.validated).containsExactly("approve-call-1 by bob");
        assertThat(second.events).containsSubsequence("artifact:response:decision=approve", "complete");
        // The resumed run continues the suspended conversation: user message + pending tool call.
        assertThat(chat.requests.get(1).messages()).extracting(ChatMessage::role)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
        assertThat(calls).isEmpty();
    }
}
