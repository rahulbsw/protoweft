package io.agentweave.evals.discovery.retrieval;

import io.agentweave.discovery.HybridIndex;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FusionOptionsTest {

    @Test
    void plainHybridUsesTheProductDefaultFusion() {
        // Regression: the default once fell back to RRF, so "catalog-hybrid" silently measured the old ranking.
        assertThat(DiscoveryRetrieverFactory.fusion(Map.of())).isEqualTo(HybridIndex.Fusion.DEFAULT);
        assertThat(DiscoveryRetrieverFactory.fusion(Map.of("fusion", "rrf")))
                .isEqualTo(HybridIndex.Fusion.rrf(60, 1, 1));
        assertThat(DiscoveryRetrieverFactory.fusion(Map.of("fusion", "convex", "alpha", "0.8")))
                .isEqualTo(HybridIndex.Fusion.convex(0.8));
    }
}
