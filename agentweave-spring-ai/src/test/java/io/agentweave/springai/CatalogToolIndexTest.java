package io.agentweave.springai;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.discovery.SourcePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogToolIndexTest {

    private static final String SEARCH = "toolSearchTool";
    private static final Map<String, Object> TWO_INTS = Map.of("type", "object",
            "properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
            "required", List.of("a", "b"));
    private static final Map<String, Object> ONE_STRING = Map.of("type", "object",
            "properties", Map.of("value", Map.of("type", "string")), "required", List.of("value"));
    private static final CallerIdentity ADMIN =
            new CallerIdentity("admin", Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("secrets:read")));

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private CapabilityCatalog catalog() {
        LocalTools math = LocalTools.builder()
                .tool("add", "Adds two integers", TWO_INTS, (args, caller) -> {
                    calls.add("add by " + caller.name());
                    return ToolOutcome.text(String.valueOf(((Number) args.get("a")).intValue()
                            + ((Number) args.get("b")).intValue()));
                })
                .tool("multiply", "Multiplies two integers", TWO_INTS, (args, caller) -> ToolOutcome.text("product"))
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
                .fallback(FallbackPolicy.of(FallbackPolicy.Mode.NONE))
                .build();
    }

    private static ChatClient chatClient(ScriptedChatModel model, CatalogToolIndex index) {
        return ChatClient.builder(model)
                .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).build())
                .defaultTools(index.toolCallbacks())
                .build();
    }

    private static String ask(ChatClient chat, String conversationId, String text) {
        return chat.prompt().user(text)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call().content();
    }

    @Test
    void firstRequestOffersOnlySearchThenFoundToolIsOfferedAndCalled() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.callTool(SEARCH, "{\"query\": \"add two integers\"}"),
                ScriptedChatModel.callTool("math__add", "{\"a\": 1, \"b\": 2}"),
                ScriptedChatModel.echoLastToolResult());

        String answer = ask(chatClient(model, CatalogToolIndex.from(catalog())), "conv-1", "what is 1 + 2?");

        assertThat(answer).isEqualTo("result: 3");
        assertThat(model.toolNames(0)).containsExactly(SEARCH);
        assertThat(model.toolNames(1)).contains(SEARCH, "math__add").doesNotContain("text__uppercase");
        assertThat(model.inputSchema(1, "math__add")).contains("\"integer\"");
        assertThat(calls).containsExactly("add by anonymous");
    }

    @Test
    void permissionHiddenToolsNeverAppear() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.callTool(SEARCH, "{\"query\": \"read a secret from the vault\"}"),
                ScriptedChatModel.echoLastToolResult());

        String answer = ask(chatClient(model, CatalogToolIndex.from(catalog())), "conv-2", "read the secret");

        assertThat(answer).isEqualTo("result: []");
        assertThat(model.prompts).hasSize(2);
        assertThat(model.toolNames(1)).containsExactly(SEARCH);
    }

    @Test
    void sessionCallerSeesScopedToolAndCallsItAsThatCaller() {
        CatalogToolIndex index = CatalogToolIndex.builder(catalog())
                .callerIdentityFrom(session -> session.startsWith("admin") ? ADMIN : CallerIdentity.ANONYMOUS)
                .build();
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.callTool(SEARCH, "{\"query\": \"read a secret from the vault\"}"),
                ScriptedChatModel.callTool("vault__read_secret", "{\"value\": \"db\"}"),
                ScriptedChatModel.echoLastToolResult());

        String answer = ask(chatClient(model, index), "admin-conv", "read the secret");

        assertThat(answer).isEqualTo("result: s3cr3t");
        assertThat(model.toolNames(1)).contains("vault__read_secret");
        assertThat(calls).containsExactly("read_secret by admin");
    }

    @Test
    void searchMapsRequestOntoCatalogQuery() {
        AtomicReference<DiscoveryResult> seen = new AtomicReference<>();
        CatalogToolIndex index = CatalogToolIndex.builder(catalog())
                .onResult((request, result) -> seen.set(result))
                .build();

        ToolSearchResponse one = index.search(new ToolSearchRequest("s", "integers", 1, null));
        ToolSearchResponse filtered = index.search(new ToolSearchRequest("s", "integers text", 5, "text, unknown"));
        ToolSearchResponse byProtocol = index.search(new ToolSearchRequest("s", "integers", 5, "local"));

        assertThat(one.toolReferences()).hasSize(1);
        assertThat(one.toolReferences().get(0).toolName()).startsWith("math__");
        assertThat(one.totalMatches()).isEqualTo(1);
        assertThat(one.searchMetadata().searchType()).isEqualTo(CatalogToolIndex.SEARCH_TYPE);
        assertThat(one.searchMetadata().query()).isEqualTo("integers");
        assertThat(filtered.toolReferences()).extracting(ToolReference::toolName).containsExactly("text__uppercase");
        assertThat(byProtocol.toolReferences()).extracting(ToolReference::toolName)
                .containsExactlyInAnyOrder("math__add", "math__multiply");
        assertThat(seen.get().stage()).isEqualTo(FallbackPolicy.Stage.NONE);
    }

    @Test
    void indexingIsANoOpAndCustomizerTightensQueries() {
        CatalogToolIndex index = CatalogToolIndex.builder(catalog())
                .queryCustomizer((request, query) -> query.sources("math"))
                .limit(10)
                .build();
        index.indexTool("s", new ToolReference("other", null, "Adds two integers"));
        index.indexTools("s", List.of(new ToolReference("more", null, "Adds")));
        index.clearIndex("s");

        Set<String> names = Set.copyOf(index.search(new ToolSearchRequest("s", "adds integers upper case", null, null))
                .toolReferences().stream().map(ToolReference::toolName).toList());

        assertThat(names).containsExactlyInAnyOrder("math__add", "math__multiply");
    }
}
