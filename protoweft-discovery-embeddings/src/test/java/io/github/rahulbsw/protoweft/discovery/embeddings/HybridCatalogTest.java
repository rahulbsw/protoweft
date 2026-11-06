package io.github.rahulbsw.protoweft.discovery.embeddings;

import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.DiscoveryResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The real offline embedder lets discovery match paraphrases that share no keywords. */
class HybridCatalogTest {

    private static LocalTools tools() {
        Map<String, Object> schema = Map.of("type", "object");
        return LocalTools.builder()
                .tool("current_conditions", "Current weather conditions and temperature for a city.", schema,
                        (a, c) -> ToolOutcome.text("ok"))
                .tool("send_email", "Send an email message to a recipient.", schema, (a, c) -> ToolOutcome.text("ok"))
                .tool("list_invoices", "List unpaid invoices for a customer account.", schema,
                        (a, c) -> ToolOutcome.text("ok"))
                .build();
    }

    @Test
    void hybridSearchFindsAParaphraseThatKeywordSearchMisses() {
        String query = "is it going to be hot outside in Madrid";

        CapabilityCatalog keywordOnly = CapabilityCatalog.builder().source("svc", tools()).build();
        DiscoveryResult keyword = keywordOnly.search(DiscoveryQuery.builder(query).limit(1).build());
        assertThat(keyword.mode()).isEqualTo(DiscoveryResult.Mode.KEYWORD_ONLY);
        assertThat(keyword.names()).doesNotContain("svc.current_conditions");

        CapabilityCatalog hybrid = CapabilityCatalog.builder().source("svc", tools())
                .embedder(OfflineEmbedders.bgeSmallEnV15()).build();
        DiscoveryResult semantic = hybrid.search(DiscoveryQuery.builder(query).limit(1).build());
        assertThat(semantic.mode()).isEqualTo(DiscoveryResult.Mode.HYBRID);
        assertThat(semantic.names().get(0)).isEqualTo("svc.current_conditions");
        // No query term matches, so the calibrated bge preset judges this LOW and broadens the result.
        assertThat(semantic.confidence().level()).isEqualTo(io.github.rahulbsw.protoweft.discovery.Confidence.Level.LOW);
        assertThat(semantic.stage()).isNotEqualTo(io.github.rahulbsw.protoweft.discovery.FallbackPolicy.Stage.NONE);
    }
}
