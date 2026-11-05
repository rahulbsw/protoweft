package io.agentweave.evals.discovery.data;

import io.agentweave.evals.discovery.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadersTest {

    @Test
    void mcpToolBenchFixture() {
        EvalDataset d = McpToolBenchLoader.load(Fixtures.mcp());
        assertThat(d.tasks()).extracting(EvalTask::id)
                .containsExactly("mcptoolbench:map-0001", "mcptoolbench:map-0002", "mcptoolbench:pay-0001",
                        "mcptoolbench:pay-0002"); // demo file skipped
        assertThat(d.tools()).containsOnlyKeys("mcptoolbench:google-maps/maps_geocode",
                "mcptoolbench:google-maps/maps_reverse_geocode", "mcptoolbench:baidu-maps-sse/map_geocode",
                "mcptoolbench:paypal/create_product", "mcptoolbench:paypal/create_invoice");
        EvalTool geo = d.tools().get("mcptoolbench:google-maps/maps_geocode");
        assertThat(geo.spec().description()).isEqualTo("Convert an address into geographic coordinates");
        assertThat(geo.spec().metadata()).containsEntry("server", "google-maps");

        EvalTask reverse = d.tasks().get(0);
        assertThat(reverse.dataset()).isEqualTo("mcptoolbench/map");
        assertThat(reverse.goldToolIds()).containsExactly("mcptoolbench:google-maps/maps_reverse_geocode");
        assertThat(reverse.goldCalls().get(0).arguments()).containsEntry("latitude", 51.5074);

        EvalTask multi = d.tasks().get(1);
        assertThat(multi.goldToolIds()).containsExactly("mcptoolbench:baidu-maps-sse/map_geocode");
        assertThat(multi.goldCalls()).hasSize(2);
        assertThat(multi.alternativeTools()).containsExactly("mcptoolbench:google-maps/maps_geocode");
        assertThat(multi.acceptsTool("mcptoolbench:google-maps/maps_geocode")).isTrue();

        assertThat(d.info().revision()).isEqualTo("fixture-rev-1");
        assertThat(d.info().sha256()).hasSize(64);
        assertThat(d.info().counts()).containsEntry("tasks", 4).containsEntry("tools", 5).containsEntry("files", 2);
    }

    @Test
    void toolRetFixture() {
        EvalDataset d = ToolRetLoader.load(Fixtures.toolret(), List.of("demo"));
        assertThat(d.tools()).hasSize(10);
        EvalTool order = d.tools().get("toolret:webdemo_tool_2");
        assertThat(order.dataset()).isEqualTo("toolret/web");
        assertThat(order.spec().name()).isEqualTo("Order Status");
        assertThat(order.rawDocument()).contains("required_parameters");
        assertThat(order.spec().inputSchema().get("required")).isEqualTo(List.of("id"));

        // query 3 has no relevant label and is dropped
        assertThat(d.tasks()).extracting(EvalTask::id)
                .containsExactly("toolret:demo_query_0", "toolret:demo_query_1", "toolret:demo_query_2");
        EvalTask graded = d.tasks().get(1);
        assertThat(graded.goldRelevance()).isEqualTo(Map.of("toolret:webdemo_tool_2", 2, "toolret:webdemo_tool_1", 1));
        assertThat(graded.instructionText()).isEmpty();
        assertThat(d.tasks().get(0).instructionText()).contains("Given a weather task, retrieve weather tools.");
        // labels given as a JSON string (raw HF format) also parse
        assertThat(d.tasks().get(2).goldToolIds()).containsExactly("toolret:webdemo_tool_3");
        assertThat(d.tasks().get(0).dataset()).isEqualTo("toolret/demo");
        assertThat(d.info().revision()).isEqualTo("fixture-queries@1 fixture-tools@1");
    }

    @Test
    void toolRetToolsOnlyAndMissingSubset() {
        assertThat(ToolRetLoader.loadTools(Fixtures.toolret()).tasks()).isEmpty();
        assertThatThrownBy(() -> ToolRetLoader.load(Fixtures.toolret(), List.of("nope")))
                .hasMessageContaining("not fetched");
        assertThatThrownBy(() -> McpToolBenchLoader.load(Fixtures.toolret().resolve("missing")))
                .hasMessageContaining("fetch script");
    }

    @Test
    void everyFixtureSchemaIsAnObjectSchema() {
        ToolRetLoader.loadTools(Fixtures.toolret()).tools().values().forEach(t -> {
            assertThat(t.spec().inputSchema()).containsEntry("type", "object").containsKey("properties");
        });
    }
}
