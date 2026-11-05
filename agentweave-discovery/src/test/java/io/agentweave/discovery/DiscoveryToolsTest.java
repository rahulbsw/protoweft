package io.agentweave.discovery;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryToolsTest {

    private final CapabilityCatalog catalog = CapabilityCatalog.builder()
            .registry(new ToolRegistry().register("files", Fixtures.files()).register("weather", Fixtures.weather()))
            .build();

    @Test
    void exposesExactlyThreeMetaTools() {
        assertThat(DiscoveryTools.of(catalog).listTools()).extracting(ToolSpec::name)
                .containsExactly("search_capabilities", "describe_capabilities", "call_capability");
    }

    @Test
    void searchDescribeCallEndToEnd() {
        DiscoverySession session = new DiscoverySession(catalog);
        DiscoveryTools tools = DiscoveryTools.forSession(session);

        ToolOutcome search = tools.invoke(ToolInvocation.of("search_capabilities", Map.of("query", "weather forecast")));
        assertThat(search.text()).contains("weather.forecast").contains("confidence:");
        assertThat(Fixtures.map(search.structuredContent().orElseThrow())).containsKey("capabilities");
        assertThat(search.text()).doesNotContain("\"properties\"").as("cards only, no schema");

        ToolOutcome describe = tools.invoke(ToolInvocation.of("describe_capabilities",
                Map.of("names", List.of("weather.forecast", "weather.nope"))));
        assertThat(describe.text()).contains("input_schema").contains("Not found: weather.nope");
        assertThat(session.loaded()).containsExactly("weather.forecast");

        ToolOutcome call = tools.invoke(ToolInvocation.of("call_capability",
                Map.of("name", "weather.forecast", "arguments", Map.of("city", "Oslo"))));
        assertThat(call.text()).isEqualTo("sunny in Oslo");
    }

    @Test
    void permissionHidingDoesNotLeakNames() {
        CapabilityCatalog guarded = CapabilityCatalog.builder()
                .source("files", Fixtures.files(), SourcePolicy.builder().requiredScopes("files:read").build()).build();
        DiscoveryTools tools = DiscoveryTools.of(guarded);

        ToolOutcome search = tools.invoke(ToolInvocation.of("search_capabilities", Map.of("query", "read a file")),
                CallerIdentity.ANONYMOUS);
        assertThat(search.text()).doesNotContain("files.");
        ToolOutcome describe = tools.invoke(ToolInvocation.of("describe_capabilities",
                Map.of("names", List.of("files.readFile"))), CallerIdentity.ANONYMOUS);
        assertThat(describe.text()).isEqualTo("Not found: files.readFile");
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("call_capability",
                Map.of("name", "files.readFile", "arguments", Map.of("path", "x"))), CallerIdentity.ANONYMOUS))
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));

        CallerIdentity reader = new CallerIdentity("r", Map.of("scopes", List.of("files:read")));
        assertThat(tools.invoke(ToolInvocation.of("search_capabilities", Map.of("query", "read a file")), reader)
                .text()).contains("files.readFile");
    }

    @Test
    void readOnlyArgumentFiltersByHint() {
        ToolOutcome r = DiscoveryTools.of(catalog).invoke(ToolInvocation.of("search_capabilities",
                Map.of("query", "file", "read_only", true, "limit", 10)));
        assertThat(r.text()).contains("files.readFile").doesNotContain("files.deleteFile");
    }

    @Test
    void invalidArgumentsAreRejected() {
        DiscoveryTools tools = DiscoveryTools.of(catalog);
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("search_capabilities", Map.of())))
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
        assertThatThrownBy(() -> tools.invoke(ToolInvocation.of("call_capability",
                Map.of("name", "weather.forecast", "arguments", "Oslo"))))
                .isInstanceOf(AgentWeaveException.class);
    }

    @Test
    void sessionEvictsLeastRecentlySelected() {
        DiscoverySession session = new DiscoverySession(catalog, CallerIdentity.ANONYMOUS, 2);
        session.select(List.of("files.readFile"));
        session.select(List.of("weather.forecast"));
        session.select(List.of("files.readFile"));
        session.select(List.of("files.listDirectory"));
        assertThat(session.loaded()).containsExactly("files.readFile", "files.listDirectory");
        assertThat(session.loadedSchemas()).extracting(ToolSpec::name)
                .containsExactly("files.readFile", "files.listDirectory");
    }
}
