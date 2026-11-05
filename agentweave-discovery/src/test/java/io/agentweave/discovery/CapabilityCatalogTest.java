package io.agentweave.discovery;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.discovery.Fixtures.FakeConnector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCatalogTest {

    private static final CallerIdentity ADMIN = new CallerIdentity("ada", Map.of("scopes", List.of("files:write")));

    private final FakeConnector mcp = new FakeConnector(ProtocolId.MCP, "2025-11-25",
            Fixtures.spec("search_issues", "Search issues in the bug tracker."));
    private final FakeConnector utcp = new FakeConnector(ProtocolId.UTCP, "1.1.0",
            Fixtures.spec("stock_quote", "Latest stock price for a ticker symbol."));

    private CapabilityCatalog catalog() {
        return CapabilityCatalog.builder()
                .source("files", Fixtures.files(), SourcePolicy.builder().toolScopes("deleteFile", "files:write").build())
                .source("weather", Fixtures.weather())
                .source("tracker", mcp)
                .source("market", utcp)
                .build();
    }

    @Test
    void buildsConciseCardsSeparateFromSchemas() {
        CapabilityCatalog catalog = catalog();
        CapabilityCard card = catalog.card("files.readFile").orElseThrow();

        assertThat(card.summary()).isEqualTo("Read the contents of a file from disk.");
        assertThat(card.protocol()).isEqualTo(CapabilityCatalog.LOCAL);
        assertThat(card.hints()).containsExactly(CapabilityHint.READ_ONLY);
        assertThat(card.tags()).containsExactly("files");
        assertThat(card.parameters()).containsExactly(new CapabilityCard.Parameter("path", true));
        assertThat(card.schemaTokensEstimate()).isPositive();
        assertThat(catalog.schema("files.readFile").orElseThrow().inputSchema()).containsKey("properties");

        CapabilityCard tracker = catalog.card("tracker.search_issues").orElseThrow();
        assertThat(tracker.protocol()).isEqualTo(ProtocolId.MCP);
        assertThat(tracker.wireRevision()).isEqualTo("2025-11-25");
        assertThat(tracker.sdkVersion()).isEqualTo("9.9");
        assertThat(catalog.size()).isEqualTo(6);
    }

    @Test
    void ranksTheRelevantCapabilityFirst() {
        DiscoveryResult r = catalog().search(DiscoveryQuery.builder("read a file").build());
        assertThat(r.names().get(0)).isEqualTo("files.readFile");
        assertThat(r.mode()).isEqualTo(DiscoveryResult.Mode.KEYWORD_ONLY);
        assertThat(r.hits().get(0).schema()).as("cards only by default").isNull();
    }

    @Test
    void exactFiltersApplyBeforeRanking() {
        CapabilityCatalog catalog = catalog();
        assertThat(catalog.search(DiscoveryQuery.builder("search issues stock price").protocols("utcp").build()).names())
                .containsExactly("market.stock_quote");
        assertThat(catalog.search(DiscoveryQuery.builder("search issues stock price").versions("2025-11-25").build())
                .names()).containsExactly("tracker.search_issues");
        assertThat(catalog.search(DiscoveryQuery.builder("file").require(CapabilityHint.READ_ONLY).caller(ADMIN).build())
                .names()).containsExactlyInAnyOrder("files.readFile", "files.listDirectory");
        assertThat(catalog.search(DiscoveryQuery.builder("file").exclude(CapabilityHint.DESTRUCTIVE).caller(ADMIN)
                .build()).names()).doesNotContain("files.deleteFile");
        assertThat(catalog.search(DiscoveryQuery.builder("forecast").sources("files").build()).names())
                .doesNotContain("weather.forecast");
    }

    @Test
    void capabilitiesNeedTheCallersScopesAndHiddenOnesAreCountedNotNamed() {
        CapabilityCatalog catalog = catalog();
        DiscoveryResult anonymous = catalog.search(DiscoveryQuery.builder("delete a file").build());
        assertThat(anonymous.names()).doesNotContain("files.deleteFile");
        assertThat(anonymous.hiddenByPermission()).isEqualTo(1);

        assertThat(catalog.search(DiscoveryQuery.builder("delete a file").caller(ADMIN).build()).names().get(0))
                .isEqualTo("files.deleteFile");
    }

    @Test
    void unavailableSourcesAreHiddenButReported() {
        CapabilityCatalog catalog = catalog();
        catalog.markUnavailable("weather", "maintenance");

        DiscoveryResult r = catalog.search(DiscoveryQuery.builder("weather forecast").build());
        assertThat(r.names()).doesNotContain("weather.forecast");
        assertThat(r.hiddenUnavailable()).containsEntry("weather", 1);

        assertThat(catalog.search(DiscoveryQuery.builder("weather forecast").includeUnavailable(true).build())
                .names()).contains("weather.forecast");
        assertThat(catalog.invoke("weather.forecast", Map.of("city", "Oslo"), CallerIdentity.ANONYMOUS).error()).isTrue();
        catalog.markAvailable("weather");
        assertThat(catalog.invoke("weather.forecast", Map.of("city", "Oslo"), CallerIdentity.ANONYMOUS).text())
                .isEqualTo("sunny in Oslo");
    }

    @Test
    void availabilityProbeIsConsultedAtSearchTime() {
        AtomicBoolean up = new AtomicBoolean(true);
        CapabilityCatalog catalog = CapabilityCatalog.builder()
                .source("weather", Fixtures.weather(), SourcePolicy.builder().availability(up::get).build()).build();
        assertThat(catalog.search(DiscoveryQuery.builder("forecast").build()).names()).hasSize(1);
        up.set(false);
        assertThat(catalog.search(DiscoveryQuery.builder("forecast").build()).hiddenUnavailable())
                .containsEntry("weather", 1);
    }

    @Test
    void lowConfidenceBroadensThenAddsNamesOnlyWithoutSchemas() {
        DiscoveryResult r = catalog().search(DiscoveryQuery.builder("translate this poem into French").build());
        assertThat(r.confidence().level()).isEqualTo(Confidence.Level.LOW);
        assertThat(r.stage()).isEqualTo(FallbackPolicy.Stage.NAMES_ONLY);
        assertThat(r.namesOnly()).isNotEmpty();
        assertThat(r.hits()).allSatisfy(h -> assertThat(h.schema()).isNull());
    }

    @Test
    void fallbackModesAllSchemasAndNone() {
        CapabilityCatalog all = CapabilityCatalog.builder().source("weather", Fixtures.weather())
                .source("files", Fixtures.files()).fallback(FallbackPolicy.of(FallbackPolicy.Mode.ALL_SCHEMAS)).build();
        DiscoveryResult baseline = all.search(DiscoveryQuery.builder("translate a poem").build());
        assertThat(baseline.stage()).isEqualTo(FallbackPolicy.Stage.ALL_SCHEMAS);
        assertThat(baseline.hits()).as("every capability, schemas inlined").hasSize(4)
                .allSatisfy(h -> assertThat(h.schema()).isNotNull());

        CapabilityCatalog none = CapabilityCatalog.builder().source("weather", Fixtures.weather())
                .fallback(FallbackPolicy.of(FallbackPolicy.Mode.NONE)).build();
        DiscoveryResult r = none.search(DiscoveryQuery.builder("translate a poem").build());
        assertThat(r.stage()).isEqualTo(FallbackPolicy.Stage.NONE);
        assertThat(r.confidence().level()).isEqualTo(Confidence.Level.LOW);
    }

    @Test
    void inlineSchemasForTopHits() {
        DiscoveryResult r = catalog().search(DiscoveryQuery.builder("file").inlineSchemas(1).caller(ADMIN).build());
        assertThat(r.hits().get(0).schema()).isNotNull();
        assertThat(r.hits().get(1).schema()).isNull();
    }

    @Test
    void refreshReembedsOnlyChangedCapabilitiesAndDropsRemovedOnes() {
        HybridIndexTest.FakeEmbedder embedder = new HybridIndexTest.FakeEmbedder(Map.of());
        CapabilityCatalog catalog = CapabilityCatalog.builder().source("tracker", mcp).source("market", utcp)
                .embedder(embedder).build();
        assertThat(embedder.documentCalls).isEqualTo(2);

        mcp.tools.add(Fixtures.spec("close_issue", "Close an issue."));
        catalog.refresh();
        assertThat(embedder.documentCalls).as("only the new capability").isEqualTo(3);

        mcp.tools.set(0, Fixtures.spec("search_issues", "Search issues and pull requests."));
        catalog.refresh("tracker");
        assertThat(embedder.documentCalls).as("only the changed capability").isEqualTo(4);
        assertThat(catalog.card("tracker.search_issues").orElseThrow().summary()).contains("pull requests");

        mcp.tools.remove(1);
        catalog.refresh("tracker");
        assertThat(catalog.card("tracker.close_issue")).isEmpty();
        assertThat(catalog.schema("tracker.close_issue")).isEmpty();
        assertThat(catalog.search(DiscoveryQuery.builder("search issues").build()).mode())
                .isEqualTo(DiscoveryResult.Mode.HYBRID);
    }

    @Test
    void aSourceThatFailsToListBecomesUnavailable() {
        CapabilityCatalog catalog = catalog();
        mcp.failListing = true;
        catalog.refresh("tracker");
        assertThat(catalog.unavailableReason("tracker")).hasValueSatisfying(r -> assertThat(r).contains("peer down"));
        assertThat(catalog.card("tracker.search_issues")).as("previous cards kept").isPresent();
        assertThat(catalog.search(DiscoveryQuery.builder("search issues").build()).hiddenUnavailable())
                .containsEntry("tracker", 1);
    }

    @Test
    void invokeTreatsForbiddenLikeUnknown() {
        CapabilityCatalog catalog = catalog();
        assertThatThrownBy(() -> catalog.invoke("files.deleteFile", Map.of("path", "x"), CallerIdentity.ANONYMOUS))
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("unknown capability: files.deleteFile");
                });
        assertThat(catalog.invoke("files.deleteFile", Map.of("path", "x"), ADMIN).text()).isEqualTo("deleted x");
        assertThat(catalog.invoke("tracker.search_issues", Map.of("q", "bug"), ADMIN).text())
                .isEqualTo("mcp:search_issues");
    }
}
