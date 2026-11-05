package io.agentweave.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceReportTest {

    private final ComplianceReport report = new ComplianceReport(
            ProtocolId.MCP, "sdk", "2.1.0", List.of("2024-11-05", "2025-11-25"), null,
            Set.of("tools/call"), Set.of("prompts"));

    @Test
    void keepsSdkVersionAndWireRevisionsSeparate() {
        assertThat(report.sdkVersion()).isEqualTo("2.1.0");
        assertThat(report.wireRevisions()).containsExactly("2024-11-05", "2025-11-25");
        assertThat(report.negotiated()).isEmpty();
        assertThat(report.withNegotiatedRevision("2025-11-25").negotiated()).contains("2025-11-25");
    }

    @Test
    void requireFailsWithUnsupportedForUnimplementedFeatures() {
        report.require("tools/call");
        assertThatThrownBy(() -> report.require("prompts"))
                .isInstanceOf(AgentWeaveException.class)
                .satisfies(e -> assertThat(((AgentWeaveException) e).category()).isEqualTo(ErrorCategory.UNSUPPORTED));
    }

    @Test
    void rejectsFeatureClaimedAsBothSupportedAndUnsupported() {
        assertThatThrownBy(() -> new ComplianceReport(ProtocolId.MCP, "sdk", "1", List.of(), null,
                Set.of("x"), Set.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
